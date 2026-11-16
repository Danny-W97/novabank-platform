"""Two explicit jobs on an immutable copy. No DEV DB access, app startup, key files or raw child logs."""
import base64
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import stat
import subprocess
import tempfile
import threading
import time
import zipfile

from operator_settings import ensure_backup_owner, regular

INSPECT = 'backup-totp-inspect'
VERIFY = 'backup-totp-verify'
ACTIONS = (INSPECT, VERIFY)
IMAGE = 'postgres:18'
DOCKER = ['docker', '--host', 'unix:///var/run/docker.sock']
MAX_DUMP = 512 * 1024 * 1024
MAX_ROWS = 100_000
PAGE = 100


def key_id(ciphertext):
    if not isinstance(ciphertext, str) or len(ciphertext) > 4096:
        raise ValueError('Nieprawidłowy szyfrogram w kopii; wartości ukryto.')
    parts = ciphertext.split('.')
    if len(parts) == 2 and all(parts):
        return 'legacy'
    if len(parts) == 4 and parts[0] == 'v1' and re.fullmatch(r'[A-Za-z0-9_-]{1,32}', parts[1]) and all(parts[2:]):
        return parts[1]
    raise ValueError('Nieznany format szyfrogramu w kopii; wartości ukryto.')


def validate_keys(parameters):
    if not isinstance(parameters, dict) or set(parameters) != {'planId', 'keys'}:
        raise ValueError('Wymagany aktualny plan i mapa kluczy; bez ścieżek i poleceń.')
    if not isinstance(parameters['planId'], str) or not re.fullmatch(r'[a-f0-9]{32}', parameters['planId']):
        raise ValueError('Nieprawidłowy identyfikator planu.')
    keys = parameters['keys']
    if not isinstance(keys, dict) or not 1 <= len(keys) <= 32:
        raise ValueError('Wymagana mapa 1–32 kluczy.')
    for identifier, value in keys.items():
        if not isinstance(identifier, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,32}', identifier):
            raise ValueError('Nieprawidłowy identyfikator klucza.')
        try:
            if not isinstance(value, str) or len(value) > 128 or len(base64.b64decode(value, validate=True)) not in (16, 24, 32):
                raise ValueError()
        except (ValueError, TypeError):
            raise ValueError('Klucz musi być AES 16/24/32 bajty w Base64; wartości ukryto.') from None
    return {'planId': parameters['planId'], 'keys': dict(keys)}


class RecoveryFailure(RuntimeError):
    pass


class TotpRecovery:
    def __init__(self, root, settings, runner=None):
        self.root = Path(root).resolve()
        self.settings = settings
        self.runner = runner or subprocess.run
        self.lock = threading.RLock()
        self.plans = {}

    def parameters(self, action, profile, parameters):
        if profile != 'dev' or action not in ACTIONS:
            raise ValueError('Test kopii TOTP jest wyłącznie dla lokalnego DEV.')
        if action == INSPECT:
            if parameters not in (None, {}):
                raise ValueError('Wykrycie korzysta wyłącznie z katalogu kopii ustawionego w CC.')
            return {}
        result = validate_keys(parameters)
        plan = self.plan(result['planId'])
        if set(result['keys']) != set(plan['keyIds']):
            raise ValueError('Podaj dokładnie klucze wykryte w tej kopii, bez dodatkowych pól.')
        return result

    def plan(self, identifier):
        with self.lock:
            self.plans = {key: value for key, value in self.plans.items() if value['expires'] > time.monotonic()}
            if identifier not in self.plans:
                raise ValueError('Plan wygasł lub panel uruchomiono ponownie. Uruchom wykrycie kopii od początku.')
            return dict(self.plans[identifier])

    def backup(self, settings):
        directory = ensure_backup_owner(self.root, settings['backupDirectory'])
        runs = sorted((path for path in directory.iterdir() if re.fullmatch(r'[0-9]{8}T[0-9]{6}Z', path.name)), reverse=True)
        for run in runs:
            if run.is_symlink() or not run.is_dir():
                raise ValueError('Nieprawidłowy katalog wykonania kopii.')
            files = [run / f'{name}_{run.name}.dump' for name in ('bank', 'shop')]
            if all(path.is_file() and Path(str(path)+'.sha256').is_file() for path in files):
                return regular(files[0], MAX_DUMP)
        raise ValueError('Brak kompletnej pary kopii bank/sklep z sumami SHA-256 w katalogu ustawionym w CC.')

    @contextmanager
    def snapshot(self, path, expected=None):
        path = regular(path, MAX_DUMP)
        metadata = regular(Path(str(path)+'.sha256'), 4096).read_text(encoding='utf8')
        # sha256sum/shasum: digest, space, text/binary marker, filename (may contain spaces).
        # Require one record; never resolve or open the filename supplied by the sidecar.
        checksum = re.fullmatch(r'([a-fA-F0-9]{64}) [ *]([^\r\n]+)(?:\r?\n)?', metadata)
        if checksum is None or not checksum[2].strip():
            raise ValueError('Brak poprawnej sumy SHA-256 kopii banku.')
        with tempfile.TemporaryFile() as stream:
            digest = hashlib.sha256()
            descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
            with os.fdopen(descriptor, 'rb') as original:
                regular(path, MAX_DUMP)
                metadata = os.fstat(original.fileno())
                if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1 or not 0 < metadata.st_size <= MAX_DUMP:
                    raise ValueError('Nieprawidłowy rozmiar lub hardlink kopii.')
                total = 0
                while chunk := original.read(1024 * 1024):
                    total += len(chunk)
                    if total > MAX_DUMP:
                        raise ValueError('Kopia przekracza limit 512 MiB.')
                    digest.update(chunk); stream.write(chunk)
            actual = digest.hexdigest()
            if actual != checksum[1].lower() or (expected is not None and actual != expected):
                raise ValueError('Kopia zmieniła się lub jej suma jest niezgodna. Nie wykonano weryfikacji kluczy.')
            stream.seek(0)
            yield stream, actual

    def call(self, args, environment, deadline, *, data=None, stdin=None, timeout=60):
        if time.monotonic() >= deadline:
            raise RecoveryFailure('Przekroczono czas testu odzyskania 2FA.')
        try:
            result = self.runner(args, input=data, stdin=stdin, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                 env=environment, timeout=min(timeout, max(.1, deadline-time.monotonic())))
            if result.returncode != 0:
                raise RecoveryFailure('Etap testu odzyskania 2FA nie powiódł się. Szczegóły procesu ukryto, aby nie ujawnić danych kopii lub kluczy.')
            if len(result.stdout) > 1_048_576:
                raise RecoveryFailure('Odpowiedź etapu przekroczyła limit; test przerwany.')
            return result.stdout
        except (OSError, subprocess.SubprocessError):
            raise RecoveryFailure('Nie uruchomiono etapu lub wystąpił timeout; wartości sekretów i dane procesu ukryto.') from None

    def rows(self, container, environment, deadline):
        after = 0; total = 0
        while True:
            sql = ("SELECT COALESCE(json_agg(row_to_json(t)),'[]'::json) FROM "
                   "(SELECT id::bigint AS id,left(totp_secret_encrypted,4097) AS encrypted FROM public.users "
                   f"WHERE id>{after} AND totp_secret_encrypted IS NOT NULL ORDER BY id LIMIT {PAGE}) t;")
            raw = self.call(DOCKER+['exec', container, 'psql', '-X', '-A', '-t', '-U', 'recovery', '-d', 'recovery',
                                   '-v', 'ON_ERROR_STOP=1', '-c', sql], environment, deadline)
            try:
                rows = json.loads(raw)
                if not isinstance(rows, list) or len(rows) > PAGE:
                    raise ValueError()
                for row in rows:
                    if set(row) != {'id', 'encrypted'} or type(row['id']) is not int or row['id'] <= after:
                        raise ValueError()
                    key_id(row['encrypted']); after = row['id']
                total += len(rows)
                if total > MAX_ROWS:
                    raise ValueError()
            except (ValueError, TypeError, KeyError):
                raise RecoveryFailure('Nieprawidłowe dane users lub przekroczony limit 100000 rekordów; wartości ukryto.') from None
            if not rows:
                return
            yield rows

    @contextmanager
    def restored(self, stream, job_id, environment, deadline, emit, cancelled):
        container = 'novabank-totp-recovery-'+job_id
        if not re.fullmatch(r'novabank-totp-recovery-[a-f0-9]{32}', container):
            raise ValueError('Nieprawidłowy identyfikator zasobu testowego.')
        created = False
        def check():
            if cancelled():
                raise RecoveryFailure('Anulowano test; trwa usuwanie zasobów testowych.')
        try:
            check()
            self.call(DOCKER+['image', 'inspect', IMAGE], environment, deadline)
            emit('Tworzenie izolowanego PostgreSQL: bez portów, sieci, wolumenów DEV i montowania plików hosta.\n')
            # Fixed image/command, no Docker socket inside. All database data is disposable tmpfs.
            created = True  # Also attempt cleanup if Docker created it but its response was lost.
            self.call(DOCKER+['run', '-d', '--rm', '--name', container, '--label', 'novabank.totp-recovery='+job_id,
                             '--network', 'none', '--log-driver', 'none', '--user', 'postgres', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                             '--memory', '1536m', '--memory-swap', '1536m', '--cpus', '1', '--pids-limit', '128',
                             '--tmpfs', '/var/lib/postgresql:rw,nosuid,nodev,noexec,size=1073741824,uid=999,gid=999',
                             '-e', 'PGDATA=/var/lib/postgresql/data', '-e', 'POSTGRES_USER=recovery',
                             '-e', 'POSTGRES_DB=recovery', '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', IMAGE,
                             'timeout', '960', '/usr/local/bin/docker-entrypoint.sh', 'postgres'], environment, deadline)
            for _ in range(60):
                check()
                try:
                    self.call(DOCKER+['exec', container, 'pg_isready', '-h', '127.0.0.1', '-U', 'recovery', '-d', 'recovery'], environment, deadline, timeout=5)
                    break
                except RecoveryFailure:
                    time.sleep(.5)
            else:
                raise RecoveryFailure('Testowy PostgreSQL nie osiągnął gotowości.')
            check(); emit('Odtwarzanie kopii banku — pg_restore --exit-on-error; żadne dane DEV nie są nadpisywane.\n')
            self.call(DOCKER+['exec', '-i', container, 'pg_restore', '--exit-on-error', '--no-owner', '--no-privileges',
                             '-U', 'recovery', '-d', 'recovery'], environment, deadline, stdin=stream, timeout=300)
            check()
            yield container, check
        finally:
            if created:
                # Never remove any other container; verify our random label before destructive cleanup.
                try:
                    present = self.call(DOCKER+['ps', '-a', '--filter', 'name=^/'+container+'$', '--format', '{{.Names}}'],
                                        environment, time.monotonic()+15, timeout=10).decode().strip()
                    if present:
                        if present != container:
                            raise RecoveryFailure('Nie potwierdzono jednoznacznego zasobu testowego.')
                        label = self.call(DOCKER+['inspect', '--format', '{{index .Config.Labels "novabank.totp-recovery"}}', container],
                                          environment, time.monotonic()+15, timeout=10).decode().strip()
                        if label != job_id:
                            raise RecoveryFailure('Nie potwierdzono właściciela kontenera testowego; sprzątanie zatrzymane.')
                        self.call(DOCKER+['rm', '-f', '-v', container], environment, time.monotonic()+30, timeout=25)
                    emit('Usunięto kontener testowy i jego dane tymczasowe. Oryginalna kopia pozostała bez zmian.\n')
                except RecoveryFailure:
                    emit('ERROR: Nie potwierdzono sprzątania kontenera testowego '+container+'. Sprawdź Docker; nie usuwaj innych zasobów.\n')
                    raise

    def run(self, action, parameters, settings, job_id, java, environment, emit, cancelled=lambda: False):
        if os.name == 'nt' or not shutil.which('docker'):
            raise ValueError('Uruchom CC w WSL z lokalnym Docker Engine. Wymagany lokalny obraz postgres:18.')
        # Only controlled local Docker and Java; no inherited cloud/Docker context, DB env or JVM injection.
        environment = {key: value for key, value in environment.items() if key in ('PATH', 'JAVA_HOME', 'HOME', 'LANG', 'LC_ALL', 'TMPDIR')}
        deadline = time.monotonic()+900
        plan = self.plan(parameters['planId']) if action == VERIFY else None
        if plan and Path(plan['path']).parent.parent != ensure_backup_owner(self.root, settings['backupDirectory']):
            raise ValueError('Katalog kopii zmienił się od wykrycia. Wykonaj nowy plan.')
        path = Path(plan['path']) if plan else self.backup(settings)
        jar = regular(self.root/'target/Bank-demo-0.0.1-SNAPSHOT.jar', 256*1024*1024)
        if not java or not jar.is_file():
            raise ValueError('Najpierw zbuduj aktualny JAR banku w CC; wymagany JDK 25.')
        try:
            with zipfile.ZipFile(jar) as archive:
                if 'BOOT-INF/classes/org/demo/bankdemo/maintenance/TotpRecoveryVerificationCommand.class' not in archive.namelist():
                    raise ValueError()
            if any(source.stat().st_mtime > jar.stat().st_mtime for source in (self.root/'src/main/java').rglob('*.java')):
                raise ValueError()
        except (ValueError, zipfile.BadZipFile, OSError):
            raise ValueError('JAR banku jest nieaktualny lub nie zawiera weryfikatora. Uruchom Buduj bank przed testem kopii.') from None
        result = None
        with self.snapshot(path, plan['sha256'] if plan else None) as (stream, digest):
            emit('Kopia banku: '+path.parent.name+'; SHA-256: '+digest+'.\n')
            with self.restored(stream, job_id, environment, deadline, emit, cancelled) as (container, check):
                counts = {}; inspected = 0
                for rows in self.rows(container, environment, deadline):
                    check()
                    for row in rows:
                        identifier = key_id(row['encrypted']); counts[identifier] = counts.get(identifier, 0)+1
                    if len(counts) > 32:
                        raise ValueError('Kopia wymaga więcej niż 32 kluczy; test przerwany.')
                    if plan:
                        if not set(counts).issubset(parameters['keys']):
                            raise ValueError('Wymagane klucze kopii zmieniły się. Wykonaj ponowne wykrycie.')
                        payload = json.dumps({'keys': parameters['keys'], 'ciphertexts': [row['encrypted'] for row in rows]}).encode()
                        try:
                            raw = self.call([java, '-Xmx128m', '-XX:-HeapDumpOnOutOfMemoryError', '-XX:ErrorFile=/dev/null',
                                             '-XX:-CreateCoredumpOnCrash', '-Dloader.main=org.demo.bankdemo.maintenance.TotpRecoveryVerificationCommand',
                                             '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher'],
                                            environment, deadline, data=payload, timeout=30)
                        except RecoveryFailure:
                            raise RecoveryFailure('NIEZALICZONY: nie potwierdzono odszyfrowania. Sprawdź zachowane klucze, zgodność kopii i aktualność JAR; wartości ukryto.') from None
                        try:
                            if json.loads(raw) != {'verified': len(rows)}:
                                raise ValueError()
                        except (ValueError, TypeError):
                            raise RecoveryFailure('Nie potwierdzono wyniku weryfikatora 2FA; wartości ukryto.') from None
                    inspected += len(rows)
                    emit(('Odszyfrowano' if plan else 'Wykryto')+f' {inspected} rekordów (bez ujawniania danych).\n')
                if not inspected:
                    raise RecoveryFailure('BRAK DANYCH: kopia nie zawiera zapisanych sekretów TOTP. Nie jest to zaliczony test odzyskania 2FA.')
                if plan:
                    if counts != plan['counts']:
                        raise RecoveryFailure('Liczba rekordów różni się od planu; test niezaliczony.')
                    result = {'verified': inspected, 'sha256': digest, 'loginTested': False, 'cleanup': True}
                else:
                    identifier = secrets.token_hex(16)
                    value = {'path': str(path), 'sha256': digest, 'keyIds': sorted(counts), 'counts': counts,
                             'records': inspected, 'expires': time.monotonic()+1200}
                    with self.lock:
                        self.plans = {key: value for key, value in self.plans.items() if value['expires'] > time.monotonic()}
                        if len(self.plans) >= 8:
                            self.plans.pop(next(iter(self.plans)))
                        self.plans[identifier] = value
                    result = {'recoveryPlan': {'planId': identifier, 'keyIds': value['keyIds'], 'records': inspected,
                                               'sha256': digest, 'backup': path.parent.name, 'expiresInSeconds': 1200}}
        emit('PODSUMOWANIE: '+('kopia odtworzona, wszystkie '+str(inspected)+' sekrety odszyfrowane.' if plan else
             'kopia odtworzona; wykryto wymagane identyfikatory kluczy. Teraz podaj klucze w formularzu.')+
             '\nNie wykonano rotacji ani logowania HTTP/TOTP; nie zmieniono env, DEV ani PROD.\n')
        return result
