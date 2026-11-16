"""Plan-first handover of exactly two user systemd timers, never arbitrary units."""
import hashlib
import hmac
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import uuid

from dev_configuration import ConfigurationConflict
from problems import ProblemError
from operator_settings import read_file, regular, atomic_json

KINDS = {"backup": ("novabank-backup", "novabank-web-dev-backup", "prune", "backup-schedule-install"),
         "dr": ("novabank-dr-test", "novabank-web-dev-dr-test", "dr-test", "dr-schedule-install")}
PROPERTIES = "LoadState,ActiveState,UnitFileState,FragmentPath,DropInPaths,Triggers,Result,ExecMainCode,ExecMainStatus,ExecMainStartTimestamp,ExecMainExitTimestamp,NextElapseUSecRealtime"


class LegacySchedules:
    def __init__(self, root, settings, key, unit_dir=None, runner=None):
        self.root = Path(root).resolve()
        self.settings = settings
        self.key = key.encode()
        self.unit_dir = Path(unit_dir) if unit_dir else Path.home() / ".config/systemd/user"
        self.runner = runner or subprocess.run

    def call(self, args, env=None):
        if os.name == "nt":
            raise RuntimeError("Migracja timerów wymaga panelu uruchomionego w WSL/Linux z systemd użytkownika.")
        try:
            result = self.runner(args, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=90 if env else 8, env=env)
        except (OSError, subprocess.TimeoutExpired):
            raise RuntimeError("Brak potwierdzenia systemd. Sprawdź stan obu timerów przed ponowieniem.") from None
        if result.returncode:
            raise RuntimeError("Operacja systemd/harmonogramu nie powiodła się. Szczegóły sprawdź w systemie; nie ujawniono treści jednostek.")
        return result.stdout[:32768]

    def show(self, name):
        raw = self.call(["systemctl", "--user", "show", name, "--property=" + PROPERTIES, "--no-pager"])
        values = dict(line.split("=", 1) for line in raw.splitlines() if "=" in line)
        if values.get("LoadState") not in ("loaded", "not-found") or values.get("DropInPaths"):
            raise ValueError("Jednostka ma nieznany stan lub dodatkowe drop-in. Wymagana ręczna diagnostyka.")
        if values["LoadState"] == "loaded" and values.get("FragmentPath") != str(self.unit_dir / name):
            raise ValueError("Jednostka pochodzi spoza oczekiwanego katalogu użytkownika.")
        if values.get("UnitFileState", "") not in ("enabled", "disabled", "", "static") or values.get("ActiveState") not in ("active", "inactive", "failed", "activating", "deactivating", "reloading"):
            raise ValueError("Nietypowy stan jednostki; migracja nie będzie wymuszana.")
        return values

    def owned_service(self, raw, operation):
        if raw is None:
            return None
        text = raw.decode("utf-8")
        lines = [line.strip() for line in text.splitlines() if line.lstrip().startswith("Exec")]
        if len(lines) != 1 or not lines[0].startswith("ExecStart="):
            raise ValueError("Niestandardowe polecenia starej usługi. Migracja zablokowana.")
        args = shlex.split(lines[0].partition("=")[2].replace("%%", "%"))
        if (len(args) != 5 or args[0] not in ("/bin/bash", "/usr/bin/bash")
                or args[1] != str(self.root / "scripts/backup/backup.sh") or args[2:4] != [operation, "--backup-dir"]
                or not args[4].startswith("/") or any(ord(c) < 32 for c in args[4])):
            raise ValueError("Stara usługa nie należy jednoznacznie do tego repozytorium.")
        return args[4]

    def plan(self, kind):
        if not isinstance(kind, str) or kind not in KINDS:
            raise ValueError("Nieznany harmonogram.")
        old, new, operation, _ = KINDS[kind]
        files = {name + suffix: read_file(self.unit_dir / (name + suffix)) for name in (old, new) for suffix in (".service", ".timer")}
        states = {name + suffix: self.show(name + suffix) for name in (old, new) for suffix in (".service", ".timer")}
        location = self.owned_service(files[old + ".service"], operation)
        timer = files[old + ".timer"]
        if timer and ([line.strip() for line in timer.decode("utf-8").splitlines() if line.strip().startswith("Unit=")] != ["Unit=" + old + ".service"] or "[Timer]" not in timer.decode("utf-8")):
            raise ValueError("Nie potwierdzono powiązania starego timera z usługą CLI.")
        values = self.settings.state("dev")
        material = json.dumps({"files": {k: hashlib.sha256(v or b"").hexdigest() for k, v in files.items()},
                               "states": states, "settings": values}, sort_keys=True).encode()
        revision = hmac.new(self.key, material, hashlib.sha256).hexdigest()
        running = any(states[name + ".service"].get("ActiveState") not in ("inactive", "failed") for name in (old, new))
        new_exists = any(files[new + suffix] is not None or states[new + suffix]["LoadState"] != "not-found" for suffix in (".service", ".timer"))
        ready = bool(location and timer and states[old + ".timer"]["LoadState"] == "loaded" and not running and not new_exists)
        return {"kind": kind, "revision": revision, "legacyTimer": old + ".timer", "newTimer": new + ".timer",
                "legacyExists": bool(location or timer), "legacyEnabled": states[old + ".timer"].get("UnitFileState") == "enabled",
                "legacyActive": states[old + ".timer"].get("ActiveState") == "active", "serviceRunning": running,
                "newExists": new_exists, "legacyBackupDirectory": location, "canMigrate": ready,
                "reason": "Gotowy plan; wymaga jawnego potwierdzenia." if ready else "Brak kompletnego timera CLI, trwa zadanie albo nowy timer już istnieje. Niczego nie przejęto.",
                "settings": values["values"]}

    def state(self):
        managed = [self.runtime(kind) for kind in KINDS]
        try:
            return {"available": True, "timers": [self.plan(kind) for kind in KINDS], "managed": managed, "note": "Odczyt nie zmienia timerów. Instalacja, poprawność konfiguracji i ostatnie wykonanie są odrębnymi wynikami."}
        except (OSError, ValueError, RuntimeError):
            return {"available": False, "timers": [], "managed": managed, "note": "Nie można bezpiecznie odczytać planów CLI. Sprawdź WSL/systemd i własność jednostek. Niczego nie przejęto."}

    def working_directory(self, unit):
        if unit not in {name + ".service" for _, name, _, _ in KINDS.values()}:
            raise ValueError("Usługa poza listą WWW.")
        raw = json.loads(self.call(["busctl", "--user", "--json=short", "call", "org.freedesktop.systemd1", "/org/freedesktop/systemd1", "org.freedesktop.systemd1.Manager", "GetUnit", "s", unit]))
        object_path = raw["data"]
        object_path = object_path[0] if isinstance(object_path, list) else object_path
        if not isinstance(object_path, str) or not object_path.startswith("/org/freedesktop/systemd1/unit/"):
            raise ValueError("Nie potwierdzono obiektu systemd.")
        raw = json.loads(self.call(["busctl", "--user", "--json=short", "get-property", "org.freedesktop.systemd1", object_path, "org.freedesktop.systemd1.Service", "WorkingDirectory"]))
        value = raw["data"]
        value = value[0] if isinstance(value, list) else value
        if not isinstance(value, str) or len(value) > 4096:
            raise ValueError("Nie potwierdzono katalogu roboczego.")
        return value

    def verify(self, kind):
        self.guard_managed(kind)  # Ownership only: a broken directory must remain repairable.
        _, name, _, _ = KINDS[kind]
        timer, service = self.show(name + ".timer"), self.show(name + ".service")
        if timer["LoadState"] != "loaded" or service["LoadState"] != "loaded":
            raise ValueError("Brak zainstalowanej pary WWW.")
        directory = self.working_directory(name + ".service")
        if directory != str(self.root) or not Path(directory).is_dir():
            raise ProblemError("SCHEDULE_CONFIGURATION_INVALID")
        if timer.get("Triggers", "").split() != [name + ".service"]:
            raise ProblemError("SCHEDULE_CONFIGURATION_INVALID")
        return timer, service, directory

    def runtime(self, kind):
        _, name, _, _ = KINDS[kind]
        result = {"kind": kind, "timer": name + ".timer", "service": name + ".service", "installed": False,
                  "enabled": False, "waiting": False, "configuration": "UNKNOWN", "configurationNote": "Nie potwierdzono konfiguracji systemd.",
                  "execution": "UNKNOWN", "lastStarted": None, "lastFinished": None, "exitCode": None, "nextRun": None}
        timer, service = {}, {}
        try:
            timer, service = self.show(name + ".timer"), self.show(name + ".service")
            result.update(installed=timer["LoadState"] == "loaded" and service["LoadState"] == "loaded",
                          enabled=timer.get("UnitFileState") == "enabled", waiting=timer["ActiveState"] == "active",
                          nextRun=timer.get("NextElapseUSecRealtime") or None)
            if timer["LoadState"] == service["LoadState"] == "not-found":
                result.update(configuration="ABSENT", configurationNote="Nie zainstalowano harmonogramu WWW.", execution="NEVER")
                return result
            result.update(lastStarted=service.get("ExecMainStartTimestamp") or None, lastFinished=service.get("ExecMainExitTimestamp") or None)
            code = service.get("ExecMainStatus", "")
            result["exitCode"] = int(code) if code.isdigit() and result["lastFinished"] else None
            if service["ActiveState"] in ("active", "activating", "deactivating", "reloading"):
                result["execution"] = "RUNNING"
            elif service.get("Result", "success") != "success":
                result["execution"] = "FAILED"
            elif not result["lastStarted"]:
                result["execution"] = "NEVER"
            elif result["lastFinished"]:
                result["execution"] = "SUCCESS" if service.get("ExecMainCode") == "1" and code == "0" else "FAILED"
            self.verify(kind)
            result.update(configuration="VALID", configurationNote="Konfiguracja systemd zweryfikowana: katalog istnieje i timer wskazuje właściwą usługę.")
        except (OSError, ValueError, RuntimeError, KeyError, IndexError, TypeError):
            if result["installed"] or any(unit.get("LoadState") == "loaded" for unit in (timer, service)):
                result.update(configuration="INVALID", configurationNote="Nie potwierdzono katalogu/własności/powiązania usługi. Nie uruchamiaj zadania przed sprawdzeniem konfiguracji.")
        return result

    def _write_unit(self, path, content):
        regular(path)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".novabank-unit-", delete=False) as stream:
                temporary = Path(stream.name)
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
            temporary.chmod(0o600)
            regular(path)
            os.replace(temporary, path)
        finally:
            if temporary and temporary.exists():
                temporary.unlink()

    def install(self, kind, environment, emit, values=None):
        """Stage → snapshot → stop timer → atomic replacement → real systemd check → enable.

        No running service is stopped. Crash safety across two files/systemd is not claimed.
        """
        self.guard_install(kind)
        _, name, operation, action = KINDS[kind]
        paths = [self.unit_dir / (name + suffix) for suffix in (".service", ".timer")]
        original = [read_file(path) for path in paths]
        previous_timer = self.show(name + ".timer")
        env = dict(environment)
        env.update(self.settings.environment(values or self.settings.state("dev")["values"]))
        env["NOVABANK_WEB_TIMER_DRY_RUN"] = "1"
        # Preserve the original migration marker when updating an existing managed pair.
        if original[0]:
            marker = next((line.removeprefix("# NovaBank migration ") for line in original[0].decode().splitlines() if line.startswith("# NovaBank migration ")), "")
            if len(marker) == 32 and all(c in "0123456789abcdef" for c in marker):
                env["NOVABANK_WEB_MIGRATION_ID"] = marker
        rendered = self.call(["bash", str(self.root / "scripts/control-center/web-operations.sh"), action, "dev"], env)
        first, second = ("--- " + str(path) + " ---\n" for path in paths)
        if not rendered.startswith(first) or rendered.count(second) != 1:
            raise ValueError("Nie potwierdzono renderowania dokładnie dwóch jednostek.")
        service_text, timer_text = rendered[len(first):].split(second)
        generated = [service_text.encode(), timer_text.encode()]
        self.owned_service(generated[0], operation)
        if [line.strip() for line in timer_text.splitlines() if line.strip().startswith("Unit=")] != ["Unit=" + name + ".service"]:
            raise ValueError("Nieprawidłowe powiązanie wygenerowanego timera.")
        archive = self.root / ".novabank/control-center/web/timer-updates" / uuid.uuid4().hex
        regular(archive / "state.json")
        archive.mkdir(parents=True, mode=0o700)
        for path, raw in zip(paths, original):
            if raw is not None:
                self._write_unit(archive / path.name, raw)
        atomic_json(archive / "state.json", {"kind": kind, "enabled": previous_timer.get("UnitFileState") == "enabled", "active": previous_timer["ActiveState"] == "active"})
        emit("Kopia jednostek i stanu: " + str(archive.relative_to(self.root)) + "\n")
        self.guard_install(kind)
        if [read_file(path) for path in paths] != original:
            raise ConfigurationConflict("Jednostki zmieniły się podczas przygotowania kopii; niczego nie nadpisano.")
        self.unit_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        try:
            if previous_timer["LoadState"] == "loaded":
                self.call(["systemctl", "--user", "stop", name + ".timer"])
            if self.show(name + ".service")["ActiveState"] not in ("inactive", "failed"):
                raise RuntimeError("Usługa rozpoczęła pracę. Nie zatrzymano jej w połowie.")
            for path, raw in zip(paths, generated):
                self._write_unit(path, raw)
            self.call(["systemctl", "--user", "daemon-reload"])
            self.verify(kind)
            self.call(["systemctl", "--user", "enable", "--now", name + ".timer"])
            timer, _, _ = self.verify(kind)
            if timer["ActiveState"] != "active" or timer.get("UnitFileState") != "enabled":
                raise RuntimeError("Nie potwierdzono włączenia timera.")
            emit("Harmonogram zainstalowany/zaktualizowany; konfiguracja systemd zweryfikowana. To nie jest test backupu/DR. Persistent może uruchomić zaległe zadanie.\n")
        except Exception:
            try:
                if self.show(name + ".service")["ActiveState"] not in ("inactive", "failed"):
                    raise RuntimeError("Nowa lub poprzednia usługa pracuje; bez automatycznego cofania.")
                self.call(["systemctl", "--user", "disable", "--now", name + ".timer"])
                for path, before, own in zip(paths, original, generated):
                    current = read_file(path)
                    if current not in (before, own):
                        raise RuntimeError("Jednostkę zmieniło inne narzędzie.")
                for path, before in zip(paths, original):
                    if before is None:
                        if path.exists():
                            regular(path).unlink()
                    else:
                        self._write_unit(path, before)
                self.call(["systemctl", "--user", "daemon-reload"])
                if original[0] and original[1]:
                    self.verify(kind)  # Do not restart a previously broken configuration.
                    if previous_timer.get("UnitFileState") == "enabled":
                        self.call(["systemctl", "--user", "enable", name + ".timer"])
                    if previous_timer["ActiveState"] == "active":
                        self.call(["systemctl", "--user", "start", name + ".timer"])
                emit("Przywrócono poprzednie jednostki i zweryfikowany stan. Kopia pozostaje w timer-updates.\n")
            except Exception:
                emit("UWAGA: nie potwierdzono pełnego cofnięcia. Sprawdź timer-updates i usługę; nie uruchamiaj drugiego timera ani nie przerywaj pracującego zadania.\n")
            raise ProblemError("SCHEDULE_UPDATE_FAILED") from None

    def guard_install(self, kind):
        plan = self.plan(kind)
        if plan["legacyEnabled"] or plan["legacyActive"] or plan["serviceRunning"]:
            raise ValueError("Istnieje aktywny harmonogram CLI albo trwa zadanie. Użyj planu migracji w Ustawienia operacji; nie uruchomiono drugiego timera.")
        self.guard_managed(kind)

    def guard_managed(self, kind):
        _, new, operation, _ = KINDS[kind]
        states = [self.show(new + suffix) for suffix in (".service", ".timer")]
        raw = read_file(self.unit_dir / (new + ".service"))
        timer = read_file(self.unit_dir / (new + ".timer"))
        if raw is None and timer is None and all(state["LoadState"] == "not-found" for state in states):
            return
        if not raw or not timer or any(state["LoadState"] != "loaded" for state in states):
            raise ValueError("Niepełna lub nieprzeładowana para jednostek WWW; sprawdź pliki i systemd ręcznie.")
        self.owned_service(raw, operation)
        if "[Timer]" not in timer.decode("utf-8") or [line.strip() for line in timer.decode("utf-8").splitlines() if line.strip().startswith("Unit=")] != ["Unit=" + new + ".service"]:
            raise ValueError("Nie potwierdzono własności timera WWW; nie wykonano operacji.")

    def migrate(self, kind, revision, environment, emit):
        plan = self.plan(kind)
        if not isinstance(revision, str) or not hmac.compare_digest(plan["revision"], revision):
            raise ConfigurationConflict("Plan timerów zmienił się. Odśwież podgląd i ponownie zatwierdź zakres.")
        if not plan["canMigrate"]:
            raise ValueError("Migracja nie spełnia warunków planu. Nie zmieniono harmonogramów.")
        old, new, _, action = KINDS[kind]
        ident = uuid.uuid4().hex
        archive = self.root / ".novabank/control-center/web/timer-migrations" / ident
        regular(archive / "plan.json")
        archive.mkdir(parents=True, mode=0o700)
        for suffix in (".service", ".timer"):
            raw = read_file(self.unit_dir / (old + suffix))
            path = regular(archive / (old + suffix))
            with path.open("xb") as stream:
                stream.write(raw)
            path.chmod(0o600)
        atomic_json(archive / "plan.json", {k: plan[k] for k in ("kind", "legacyTimer", "newTimer", "legacyEnabled", "legacyActive")})
        # Recheck after copying; a stale plan never disables an unexpected timer.
        if self.plan(kind)["revision"] != revision:
            raise ConfigurationConflict("Plan zmienił się podczas przygotowania kopii. Timerów nie zmieniono.")
        env = dict(environment)
        env.update(self.settings.environment(plan["settings"]))
        env["NOVABANK_WEB_MIGRATION_ID"] = ident
        installed = False
        try:
            self.call(["systemctl", "--user", "disable", "--now", old + ".timer"])
            if self.show(old + ".service")["ActiveState"] == "active":
                raise RuntimeError("Stara usługa właśnie rozpoczęła pracę. Poczekaj na zakończenie; nie zatrzymujemy backupu/DR w połowie.")
            self.install(kind, env, emit, plan["settings"])
            installed = True
            state = self.show(new + ".timer")
            if state.get("ActiveState") != "active" or state.get("UnitFileState") != "enabled":
                raise RuntimeError("Nie potwierdzono uruchomienia nowego timera.")
            emit("Migracja zakończona: stary timer wyłączony, nowy włączony. Kopii baz nie przenoszono i nie usuwano. Jednostki CLI pozostają oraz mają kopię w timer-migrations/" + ident + ". Sprawdź log harmonogramu; Persistent może uruchomić zaległe zadanie.\n")
        except Exception:
            # Never re-enable old automation unless the new timer is confirmed stopped.
            try:
                new_files = [self.unit_dir / (new + suffix) for suffix in (".service", ".timer")]
                for path in new_files:
                    raw = read_file(path)
                    if raw is not None and ("# NovaBank migration " + ident).encode() not in raw:
                        raise RuntimeError("Nowa jednostka została zmieniona; automatyczne cofnięcie zablokowane.")
                if installed or any(path.exists() for path in new_files):
                    self.call(["systemctl", "--user", "disable", "--now", new + ".timer"])
                    if self.show(new + ".service")["ActiveState"] == "active":
                        raise RuntimeError("Nowe zadanie działa; wymagana kontrola operatora.")
                    for path in new_files:
                        regular(path)
                        if path.exists():
                            path.unlink()
                    self.call(["systemctl", "--user", "daemon-reload"])
                if plan["legacyEnabled"]:
                    self.call(["systemctl", "--user", "enable", old + ".timer"])
                if plan["legacyActive"]:
                    self.call(["systemctl", "--user", "start", old + ".timer"])
                emit("Próba migracji nieudana; przywrócono poprzednie włączenie/aktywność timera CLI. Sprawdź kopię jednostek i logi przed kolejną próbą.\n")
            except Exception:
                emit("UWAGA: nie potwierdzono pełnego cofnięcia. Nie uruchamiaj drugiego harmonogramu. Sprawdź oba timery/usługi i kopię timer-migrations/" + ident + " ręcznie.\n")
            raise ProblemError("TIMER_MIGRATION_UNCONFIRMED") from None
