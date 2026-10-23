"""Fixed local DEV/TEST files, key/value editing and explicit secret reveal.

No caller-supplied paths, shell evaluation, downloads or production configuration.
"""
import base64
import hashlib
import hmac
import os
from pathlib import Path
import re
import tempfile
import threading

from dev_configuration import ConfigurationConflict, assignments, upsert, validate, FIELD_MAP
from env_ownership import OwnershipConflict, normalize_pair, owner

FILES = {
    "project": (".novabank/env/project.env", "dev"),
    "dev": (".novabank/env/dev.env", "dev"),
    "monitoring": (".novabank/monitoring/.env.monitoring", "dev"),
    "test": (".novabank/env/test.env", "test"),
}
TEST_KEYS = {
    "TEST_POSTGRES_URL", "TEST_POSTGRES_USER", "TEST_POSTGRES_PASSWORD",
    "TEST_SHOP_POSTGRES_URL", "TEST_SHOP_POSTGRES_USER", "TEST_SHOP_POSTGRES_PASSWORD",
    "TEST_REDIS_HOST", "TEST_REDIS_PORT", "TEST_TOTP_ENCRYPTION_KEY", "TEST_TOTP_ENCRYPTION_KEYS",
    "TEST_TOTP_ENCRYPTION_ACTIVE_ID", "TEST_BANK_API_KEY",
    "TEST_BANK_BASE_URL", "TEST_BANK_API_KEY_ID", "TEST_SHOP_MERCHANT_ACCOUNT", "TEST_SHOP_MERCHANT_ID",
}
SECRET = re.compile(r"password|secret|token|api.?keys?|encryption.?key|account|merchant", re.I)
RISK = re.compile(r"PASSWORD|SECRET|API_KEYS?$|TOTP_ENCRYPTION_(?:KEY|ACTIVE_ID)|MERCHANT_ID|API_KEY_MERCHANTS|PAYMENT_MERCHANTS|POSTGRES_(?:DB|USER)")
PREFIX = re.compile(r"^(?:POSTGRES_|SHOP_|BANK_|REDIS_|METRICS_|TOTP_|GRAFANA_|MANAGEMENT_)")
BLOCKED = re.compile(r"(?:^|_)(?:PROD|VAULT|SSL|TLS|KEYSTORE|TRUSTSTORE)(?:_|$)")


def secret(key, value=""):
    if key in ("SHOP_MERCHANT_ID", "TEST_SHOP_MERCHANT_ID", "BANK_API_KEY_MERCHANTS", "TOTP_ENCRYPTION_ACTIVE_ID"):
        return False
    return bool(SECRET.search(key) or re.search(r"(?<!\d)\d{26}(?!\d)|://[^/\s]*:[^/\s]*@", value))


def editable(key, file):
    if file == "test":
        return key in TEST_KEYS
    if key == "SPRING_PROFILES_ACTIVE":
        return False
    if file in ("project", "dev"):
        return bool(PREFIX.match(key)) and owner(key) == file and not BLOCKED.search(key)
    return bool(PREFIX.match(key)) and not BLOCKED.search(key)


def validate_entry(key, value, file):
    if not isinstance(key, str) or not re.fullmatch(r"[A-Z][A-Z0-9_]{0,95}", key) or not editable(key, file):
        expected = owner(key) if isinstance(key, str) and file in ("project", "dev") else None
        raise ValueError(f"Ustawienie należy do {expected}.env, nie do {file}.env." if expected else "Niedozwolona nazwa ustawienia dla tego pliku/profilu.")
    if not isinstance(value, str) or len(value) > 2048 or any(c in value for c in "\r\n\0\\$`\"'") or any(ord(c) < 32 for c in value):
        raise ValueError(f"Pole {key}: niedozwolony format; bez poleceń shell, cudzysłowów i nowych linii.")
    if re.search(r"\s|[;|<>#()]", value) or (file in ("project", "monitoring") and re.search(r"[&{}]", value)):
        raise ValueError(f"Pole {key}: użyj pojedynczej wartości bez spacji i operatorów shell.")
    if key in FIELD_MAP and FIELD_MAP[key][3] not in ("managed",):
        validate(key, value)
    if key.endswith("TOTP_ENCRYPTION_KEY"):
        try:
            if len(base64.b64decode(value, validate=True)) != 32:
                raise ValueError()
        except ValueError:
            raise ValueError(f"Pole {key}: wymagany klucz 32 bajty zapisany jako Base64.") from None
    if key in ("SHOP_MERCHANT_ID", "TEST_SHOP_MERCHANT_ID") and value and not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", value):
        raise ValueError("Nieprawidłowy identyfikator sprzedawcy.")
    if key == "BANK_API_KEY_MERCHANTS" and value and not re.fullmatch(r"[A-Za-z0-9_-]{1,64}=[A-Za-z0-9_-]{1,64}(?:,[A-Za-z0-9_-]{1,64}=[A-Za-z0-9_-]{1,64})*", value):
        raise ValueError("Mapowanie kluczy: key-id=merchant-id,old-key-id=merchant-id.")
    if key == "BANK_PAYMENT_MERCHANTS" and value and not re.fullmatch(r"[A-Za-z0-9_-]{1,64}=[0-9]{26}(?:\+[0-9]{26})*(?:,[A-Za-z0-9_-]{1,64}=[0-9]{26}(?:\+[0-9]{26})*)*", value):
        raise ValueError("Mapowanie rachunków: merchant-id=rachunek,other-merchant-id=rachunek.")
    if key in ("TOTP_ENCRYPTION_KEYS", "TEST_TOTP_ENCRYPTION_KEYS") and value:
        seen = set()
        for entry in value.split(","):
            identifier, separator, encoded = entry.partition("=")
            try:
                if not separator or not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", identifier) or identifier in seen or len(base64.b64decode(encoded, validate=True)) != 32:
                    raise ValueError()
            except ValueError:
                raise ValueError("Keyring TOTP: unikalne key-id=klucz-Base64 (32 bajty), oddzielone przecinkiem.") from None
            seen.add(identifier)
    if re.match(r"(?:sk|rk|pk)_live_", value):
        raise ValueError("Panel DEV nie dopuszcza produkcyjnych kluczy Stripe.")
    if key.endswith("HOST") or key.endswith("ADDRESS"):
        if value not in ("localhost", "127.0.0.1", "0.0.0.0"):
            raise ValueError(f"Pole {key}: dozwolony tylko lokalny adres.")
    if key.endswith("PORT"):
        if not re.fullmatch(r"[0-9]{1,5}", value) or not 1024 <= int(value) <= 65535:
            raise ValueError(f"Pole {key}: wymagany port 1024–65535.")
    if key in ("BANK_API_URL", "SHOP_PAYMENT_CALLBACK_URL", "TEST_BANK_BASE_URL"):
        if not re.fullmatch(r"http://(?:localhost|127\.0\.0\.1):[0-9]{4,5}(?:/[A-Za-z0-9/_-]*)?", value):
            raise ValueError(f"Pole {key}: wymagany lokalny adres HTTP z portem.")
    if file == "test" and key.endswith("POSTGRES_URL"):
        database = "shop_test" if "SHOP" in key else "bank_test"
        if not re.fullmatch(r"jdbc:postgresql://(?:localhost|127\.0\.0\.1):[0-9]{4,5}/" + database, value):
            raise ValueError(f"Pole {key}: tylko lokalna baza {database}; nie baza DEV/PROD.")
    return value


class ConfigurationFiles:
    def __init__(self, root, token, lock=None):
        self.root = Path(root).resolve()
        self.key = token.encode()
        self.lock = lock or threading.RLock()

    def target(self, file):
        if file not in FILES:
            raise ValueError("Plik spoza listy DEV/TEST.")
        path = self.root / FILES[file][0]
        for node in (path, *list(path.parents)[:-1]):
            if node == self.root:
                break
            if node.is_symlink() or (hasattr(node, "is_junction") and node.is_junction()):
                raise ValueError("Dowiązania plików/katalogów konfiguracji są niedozwolone.")
        if not path.resolve().is_relative_to(self.root):
            raise ValueError("Plik poza projektem.")
        if path.exists() and (not path.is_file() or path.stat().st_nlink != 1):
            raise ValueError("Konfiguracja musi być zwykłym plikiem bez hardlink.")
        return path

    def snapshot(self):
        result = {}
        for file in FILES:
            path = self.target(file)
            if path.exists() and path.stat().st_size > 131072:
                raise ValueError("Plik konfiguracji przekracza 128 KB.")
            raw = path.read_bytes() if path.exists() else None
            if raw is not None:
                raw.decode("utf-8-sig")
            result[file] = raw
        return result

    def revision(self, snapshot):
        digest = hmac.new(self.key, digestmod=hashlib.sha256)
        for file, raw in snapshot.items():
            digest.update(file.encode() + b"\0" + (b"absent" if raw is None else b"present" + raw))
        return digest.hexdigest()

    def state(self, profile):
        if profile not in ("dev", "test"):
            raise ValueError("Edytor dostępny tylko dla DEV/TEST.")
        with self.lock:
            snapshot = self.snapshot()
            files = []
            for file, (relative, owner) in FILES.items():
                if owner != profile:
                    continue
                raw = snapshot[file]
                entries = [{"key": key, "value": None if secret(key, value) else value,
                            "secret": secret(key, value), "configured": bool(value), "editable": editable(key, file)}
                           for key, value in assignments(raw or b"").items()]
                files.append({"id": file, "path": relative, "exists": raw is not None,
                              "size": len(raw or b""), "entries": entries})
            return {"profile": profile, "revision": self.revision(snapshot), "files": files,
                    "note": "project.env: niewrażliwa infrastruktura. dev.env: sekrety i ustawienia DEV, bez kopii w project.env. Pole można zapisać tylko w jego docelowym pliku. Zapis nie zmienia haseł w PostgreSQL ani działających procesów; TOTP wymaga migracji danych. TEST jest przekazywany wyłącznie do testów Maven; Testcontainers może nadpisać endpointy."}

    def reveal(self, profile, file, key, revision, confirmed):
        if confirmed is not True:
            raise ValueError("Podgląd sekretu wymaga potwierdzenia.")
        self.check_file(profile, file)
        with self.lock:
            snapshot = self.snapshot()
            self.check_revision(revision, snapshot)
            values = assignments(snapshot[file] or b"")
            if not isinstance(key, str) or key not in values or BLOCKED.search(key) or not secret(key, values[key]):
                raise ValueError("Nie znaleziono wskazanego sekretu.")
            return {"key": key, "value": values[key]}

    def check_file(self, profile, file):
        if not isinstance(file, str) or file not in FILES or FILES[file][1] != profile:
            raise ValueError("Plik nie należy do wybranego profilu DEV/TEST.")

    def check_revision(self, revision, snapshot):
        if not isinstance(revision, str) or not re.fullmatch(r"[0-9a-f]{64}", revision) or not hmac.compare_digest(revision, self.revision(snapshot)):
            raise ConfigurationConflict("Pliki zmieniły się. Wczytaj edytor ponownie przed zapisem/podglądem.", "CONFIG_REVISION_CHANGED")

    def save(self, profile, file, revision, values, remove, confirmed, acknowledge_risk):
        self.check_file(profile, file)
        if confirmed is not True or not isinstance(values, dict) or len(values) > 128 or not isinstance(remove, list) or len(remove) > 128:
            raise ValueError("Wymagane potwierdzenie, mapa ustawień i lista usuwanych pól (maks. 128).")
        if any(not isinstance(key, str) or not editable(key, file) for key in remove) or set(values).intersection(remove):
            raise ValueError("Nieprawidłowa lista usuwanych ustawień.")
        for key, value in values.items():
            validate_entry(key, value, file)
        with self.lock:
            original = self.snapshot()
            self.check_revision(revision, original)
            base = dict(original)
            if file in ("project", "dev", "monitoring"):
                try:
                    pair, _ = normalize_pair(original)
                except OwnershipConflict as error:
                    raise ConfigurationConflict(str(error)) from None
                base.update(pair)
            previous = assignments(base[file] or b"")
            touched = set(remove) | {key for key, value in values.items() if value != previous.get(key)}
            if any(RISK.search(key) and previous.get(key) for key in touched) and acknowledge_risk is not True:
                raise ConfigurationConflict("Zmiana poświadczeń lub kluczy wymaga potwierdzenia skutków: zgodność z bazą, restart aplikacji, migracja danych 2FA.")
            raw = base[file] if base[file] is not None else b"# Lokalna konfiguracja Control Center. Nie commituj.\n"
            if remove:
                raw = b"".join(line for line in raw.splitlines(keepends=True) if line.decode("utf-8-sig").partition("=")[0].strip() not in remove)
            updated = upsert(raw, values)
            if file == "dev":
                updated = upsert(updated, {"SPRING_PROFILES_ACTIVE": "dev"})
            new = {name: raw for name, raw in base.items() if raw != original[name]}
            new[file] = updated
            # Monitoring credentials consumed by Spring must agree with its scrape config.
            # Prometheus YAML is generated separately by the existing monitoring setup action.
            if file == "monitoring":
                metrics = {key: value for key, value in values.items() if key in ("METRICS_USERNAME", "METRICS_PASSWORD")}
                if metrics:
                    new["dev"] = upsert(new.get("dev", base["dev"]) or b"# Ustawienia DEV.\n", metrics)
            if file in ("project", "dev") and original["monitoring"] is not None:
                metrics = {key: value for key, value in values.items() if key in ("METRICS_USERNAME", "METRICS_PASSWORD")}
                if metrics:
                    new["monitoring"] = upsert(original["monitoring"], metrics)
            if file in ("project", "dev"):
                derived = {}
                for port, url, suffix in (("BANK_APP_PORT", "BANK_API_URL", ""), ("SHOP_APP_PORT", "SHOP_PAYMENT_CALLBACK_URL", "/payment/callback")):
                    if port in values and url not in values:
                        derived[url] = "http://localhost:" + values[port] + suffix
                if derived:
                    new["dev"] = upsert(new.get("dev", original["dev"]) or b"# Ustawienia DEV.\n", derived)
                effective = assignments(new.get("project", original["project"]) or b"") | assignments(new.get("dev", original["dev"]) or b"")
                ports = [value for key, value in effective.items() if key in FIELD_MAP and FIELD_MAP[key][3] == "port"]
                if len(ports) != len(set(ports)):
                    raise ValueError("Porty baz, Redis, aplikacji i Actuator muszą być różne.")
            self.commit(original, new)
            result = self.state(profile)
            result["message"] = "Zapisano: " + ", ".join(FILES[item][0] for item in new) + ". "
            result["message"] += "Ustawienia zostaną wczytane przy następnym uruchomieniu testów Maven." if profile == "test" else "Zrestartuj zależne procesy; haseł w bazie nie zmieniono. Po zmianie metryk uruchom Przygotuj monitoring DEV, aby odświeżyć Prometheus."
            return result

    def commit(self, original, new):
        staged, backups, committed = {}, {}, []
        keep_backups = False
        def stage(file, raw):
            target = self.target(file)
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            with tempfile.NamedTemporaryFile(dir=target.parent, prefix=".control-config-", delete=False) as stream:
                path = Path(stream.name)
                try:
                    path.chmod(0o600)
                    stream.write(raw)
                    stream.flush()
                    os.fsync(stream.fileno())
                except BaseException:
                    stream.close()
                    path.unlink(missing_ok=True)
                    raise
            return path
        try:
            for file, raw in new.items():
                staged[file] = stage(file, raw)
                if original[file] is not None:
                    backups[file] = stage(file, original[file])
            self.check_revision(self.revision(original), self.snapshot())
            for file, temporary in staged.items():
                os.replace(temporary, self.target(file))
                committed.append(file)
        except BaseException:
            for file in reversed(committed):
                try:
                    if file in backups:
                        os.replace(backups[file], self.target(file))
                    else:
                        self.target(file).unlink(missing_ok=True)
                except OSError:
                    keep_backups = True
            if keep_backups:
                raise RuntimeError("Błąd wycofania zapisu. Zachowano .control-config-* przy plikach; sprawdź je przed startem.") from None
            raise
        finally:
            for path in [*staged.values(), *([] if keep_backups else backups.values())]:
                try:
                    path.unlink(missing_ok=True)
                except OSError:
                    pass

    def test_environment(self):
        with self.lock:
            values = assignments(self.snapshot()["test"] or b"")
            for key, value in values.items():
                validate_entry(key, value, "test")
            return values
