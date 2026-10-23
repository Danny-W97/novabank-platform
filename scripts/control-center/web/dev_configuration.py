"""Allowlisted DEV setup. Never returns secrets or evaluates dotenv as shell code."""
import base64
import hashlib
import hmac
import os
from pathlib import Path
import re
import secrets
import tempfile
import threading

from env_ownership import OwnershipConflict, normalize_pair


class ConfigurationConflict(Exception):
    def __init__(self, message, issue_code="CONFIG_CONFLICT_MANUAL"):
        self.issue_code = issue_code
        super().__init__(message)


FIELDS = (
    ("POSTGRES_HOST", "Host bazy banku", "Baza banku", "host", "localhost", "project", False),
    ("POSTGRES_PORT", "Port bazy banku", "Baza banku", "port", "5300", "project", False),
    ("POSTGRES_DB", "Nazwa bazy banku", "Baza banku", "name", "bankdb", "project", True),
    ("POSTGRES_USER", "Użytkownik bazy banku", "Baza banku", "name", "bankuser", "project", True),
    ("POSTGRES_PASSWORD", "Hasło bazy banku", "Baza banku", "secret", "", "dev", True),
    ("SHOP_POSTGRES_HOST", "Host bazy sklepu", "Baza sklepu", "host", "localhost", "project", False),
    ("SHOP_POSTGRES_PORT", "Port bazy sklepu", "Baza sklepu", "port", "5400", "project", False),
    ("SHOP_POSTGRES_DB", "Nazwa bazy sklepu", "Baza sklepu", "name", "shopdb", "project", True),
    ("SHOP_POSTGRES_USER", "Użytkownik bazy sklepu", "Baza sklepu", "name", "shopuser", "project", True),
    ("SHOP_POSTGRES_PASSWORD", "Hasło bazy sklepu", "Baza sklepu", "secret", "", "dev", True),
    ("REDIS_HOST", "Host Redis", "Redis", "host", "localhost", "project", False),
    ("REDIS_PORT", "Port Redis", "Redis", "port", "6379", "project", False),
    ("BANK_APP_PORT", "Port banku", "Aplikacje i płatności", "port", "8080", "dev", False),
    ("SHOP_APP_PORT", "Port sklepu", "Aplikacje i płatności", "port", "8081", "dev", False),
    ("BANK_MANAGEMENT_PORT", "Port Actuator banku", "Monitoring", "port", "8082", "dev", False),
    ("SHOP_MANAGEMENT_PORT", "Port Actuator sklepu", "Monitoring", "port", "8083", "dev", False),
    ("METRICS_USERNAME", "Login monitoringu", "Monitoring", "name", "metrics-reader", "dev", False),
    ("METRICS_PASSWORD", "Hasło monitoringu", "Monitoring", "secret", "", "dev", True),
    ("SHOP_MERCHANT_ACCOUNT_NUMBER", "Rachunek sklepu (26 cyfr)", "Aplikacje i płatności", "account", "", "dev", False),
    ("BANK_API_KEY", "Klucz API bank ↔ sklep", "Stabilne klucze DEV", "managed", "", "dev", True),
    ("TOTP_ENCRYPTION_KEY", "Klucz szyfrowania sekretów 2FA", "Stabilne klucze DEV", "managed", "", "dev", True),
)
FIELD_MAP = {field[0]: field for field in FIELDS}
PLACEHOLDERS = ("change-me", "replace-with-", "generate-a-", "generate-")
DB_IDENTITY = {"POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD", "SHOP_POSTGRES_DB", "SHOP_POSTGRES_USER", "SHOP_POSTGRES_PASSWORD"}


def real(value):
    return bool(value) and not value.lower().startswith(PLACEHOLDERS)


def assignments(raw):
    result = {}
    for line in raw.decode("utf-8-sig").splitlines():
        key, sep, value = line.partition("=")
        if sep and re.fullmatch(r"[A-Z][A-Z0-9_]*", key.strip()):
            result[key.strip()] = value.strip().strip("\"'")
    return result


def validate(key, value):
    kind = FIELD_MAP[key][3]
    if not isinstance(value, str) or len(value) > 256 or any(char in value for char in "\r\n\x00\\$`\"'"):
        raise ValueError(f"Nieprawidłowa wartość pola {key}. Nie używaj znaków sterujących, cytowania ani ekspansji shell.")
    if kind == "port":
        valid = bool(re.fullmatch(r"[1-9][0-9]{3,4}", value)) and 1024 <= int(value) <= 65535
    elif kind == "host":
        valid = value in ("localhost", "127.0.0.1")
    elif kind == "name":
        valid = bool(re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{0,62}", value))
        if key in DB_IDENTITY:
            valid = valid and "-" not in value
    elif kind == "secret":
        valid = real(value) and bool(re.fullmatch(r"[A-Za-z0-9_+=./!:@%\-]{16,256}", value))
    elif kind == "account":
        valid = not value or bool(re.fullmatch(r"[0-9]{26}", value))
    else:
        raise ValueError("Stabilne klucze są zarządzane automatycznie, bez rotacji w formularzu.")
    if not valid:
        raise ValueError(f"Nieprawidłowa wartość pola {key}. Sprawdź format opisany przy polu.")
    return value


def upsert(raw, values):
    newline = "\r\n" if b"\r\n" in raw else "\n"
    lines = raw.decode("utf-8-sig").splitlines()
    result, seen = [], set()
    for line in lines:
        key, sep, _ = line.partition("=")
        key = key.strip()
        if sep and key in values:
            if key not in seen:
                result.append(key + "=" + values[key])
                seen.add(key)
        else:
            result.append(line)
    result.extend(key + "=" + value for key, value in values.items() if key not in seen)
    bom = b"\xef\xbb\xbf" if raw.startswith(b"\xef\xbb\xbf") else b""
    return bom + (newline.join(result) + newline).encode("utf-8")


class DevConfiguration:
    def __init__(self, root, token):
        self.root = Path(root).resolve()
        self.directory = self.root / ".novabank/env"
        self.key = token.encode("utf-8")
        self.lock = threading.RLock()

    def snapshot(self):
        # Fixed targets; refuse symlinks/junctions outside this checkout and hardlinks.
        for path in (self.root / ".novabank", self.directory):
            if path.is_symlink() or getattr(path, "is_junction", lambda: False)() or (path.exists() and not path.resolve().is_relative_to(self.root)):
                raise ValueError("Katalog konfiguracji musi znajdować się w tym repozytorium, bez dowiązań.")
        result = {}
        for name in ("project", "dev"):
            path = self.directory / f"{name}.env"
            if path.is_symlink() or (path.exists() and (not path.is_file() or path.stat().st_nlink != 1)):
                raise ValueError("Pliki konfiguracji nie mogą być dowiązaniami ani katalogami.")
            if path.exists() and path.stat().st_size > 128_000:
                raise ValueError("Plik konfiguracji jest zbyt duży do bezpiecznej edycji w panelu.")
            raw = path.read_bytes() if path.exists() else None
            assignments(raw or b"")  # Validate encoding before any write.
            result[name] = raw
        return result

    def revision(self, snapshot):
        digest = hmac.new(self.key, digestmod=hashlib.sha256)
        for name, raw in snapshot.items():
            digest.update(name.encode() + b"\0" + (b"absent" if raw is None else b"present" + raw))
        return digest.hexdigest()

    def state(self):
        with self.lock:
            snapshot = self.snapshot()
            project, dev = (assignments(snapshot[name] or b"") for name in ("project", "dev"))
            effective = project | dev
            fields, warnings = [], []
            db_locked = {"POSTGRES": real(effective.get("POSTGRES_PASSWORD", "")),
                         "SHOP_POSTGRES": real(effective.get("SHOP_POSTGRES_PASSWORD", ""))}
            for key, label, group, kind, default, file, protected in FIELDS:
                value = effective.get(key, "")
                configured = real(value)
                locked = kind == "managed" or (protected and configured)
                if key in DB_IDENTITY:
                    locked = db_locked["SHOP_POSTGRES" if key.startswith("SHOP_") else "POSTGRES"]
                display = None
                if kind not in ("secret", "account", "managed"):
                    try:
                        display = validate(key, value or default)
                    except ValueError:
                        warnings.append(f"Pole {key} ma niepoprawną wartość. Popraw je przed zapisem.")
                        display = ""  # Do not hide a bad stored value behind a valid-looking default.
                fields.append({"key": key, "label": label, "group": group, "kind": kind,
                               "value": display, "configured": configured, "locked": locked,
                               "file": f".novabank/env/{file}.env"})
            for key in (*DB_IDENTITY, "REDIS_HOST", "REDIS_PORT", "POSTGRES_HOST", "POSTGRES_PORT", "SHOP_POSTGRES_HOST", "SHOP_POSTGRES_PORT"):
                if real(project.get(key, "")) and key in dev and dev[key] != project[key]:
                    warnings.append(f"Niezgodność {key} między project.env i dev.env. Nie zmieniaj poświadczeń działającej bazy bez migracji.")
            try:
                normalize_pair(snapshot)
            except OwnershipConflict as error:
                warnings.append(str(error))
            totp = effective.get("TOTP_ENCRYPTION_KEY", "")
            if real(totp):
                try:
                    if len(base64.b64decode(totp, validate=True)) != 32:
                        raise ValueError()
                except ValueError:
                    warnings.append("Istniejący TOTP_ENCRYPTION_KEY ma niepoprawny format. Nie zastępuj go automatycznie — sprawdź źródło klucza.")
            required = ("POSTGRES_PASSWORD", "SHOP_POSTGRES_PASSWORD", "BANK_API_KEY", "TOTP_ENCRYPTION_KEY")
            ready = all(real(effective.get(key, "")) for key in required) and all(raw is not None for raw in snapshot.values()) and not warnings
            return {"profile": "dev", "revision": self.revision(snapshot),
                    "files": [{"path": f".novabank/env/{name}.env", "exists": raw is not None} for name, raw in snapshot.items()],
                    "fields": fields, "ready": ready, "paymentsReady": bool(
                        re.fullmatch(r"[0-9]{26}", effective.get("SHOP_MERCHANT_ACCOUNT_NUMBER", ""))
                        and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", effective.get("SHOP_MERCHANT_ID", ""))
                        and effective.get("BANK_API_KEY_MERCHANTS") and effective.get("BANK_PAYMENT_MERCHANTS")),
                    "warnings": warnings,
                    "note": "Sekrety i numer rachunku nie są odczytywane do formularza. Puste pole zachowuje zapisany sekret. Zapis nie uruchamia baz ani aplikacji. Istniejące hasła baz i klucz TOTP nie są rotowane."}

    def save(self, revision, values, confirmed):
        if confirmed is not True:
            raise ValueError("Utworzenie lub zapis plików wymaga potwierdzenia.")
        if not isinstance(revision, str) or not re.fullmatch(r"[0-9a-f]{64}", revision):
            raise ValueError("Nieprawidłowa rewizja konfiguracji.")
        if not isinstance(values, dict) or set(values) - set(FIELD_MAP):
            raise ValueError("Dopuszczone są wyłącznie pola formularza DEV.")
        with self.lock:
            original = self.snapshot()
            if not hmac.compare_digest(revision, self.revision(original)):
                raise ConfigurationConflict("Pliki zmieniły się od odczytu formularza. Wczytaj je ponownie; zapis został odrzucony.", "CONFIG_REVISION_CHANGED")
            current = self.state()
            try:
                canonical, _ = normalize_pair(original)
            except OwnershipConflict as error:
                raise ConfigurationConflict(str(error)) from None
            project, dev = (assignments(canonical[name] or b"") for name in ("project", "dev"))
            effective = project | dev
            changes = {"project": {}, "dev": {"SPRING_PROFILES_ACTIVE": "dev"}}
            metadata = {field["key"]: field for field in current["fields"]}
            for key, value in values.items():
                if not isinstance(value, str):
                    raise ValueError("Każda wartość formularza musi być tekstem.")
                kind = FIELD_MAP[key][3]
                if kind == "managed":
                    raise ValueError("Klucze API/TOTP nie mogą być podmieniane przez formularz.")
                if kind in ("secret", "account") and not value:
                    continue
                if metadata[key]["locked"] and value != effective.get(key, ""):
                    raise ConfigurationConflict(f"Pole {key} jest chronione. Zmiana poświadczeń istniejącej bazy wymaga osobnej procedury.")
                validate(key, value)
                changes[FIELD_MAP[key][5]][key] = value
            # Preserve values; legacy assignments are routed to their sole owner.
            for key, _, _, kind, default, file, _ in FIELDS:
                if key in changes[file]:
                    continue
                value = effective.get(key, "")
                if kind == "managed":
                    if not real(value):
                        value = base64.b64encode(secrets.token_bytes(32)).decode() if key == "TOTP_ENCRYPTION_KEY" else secrets.token_hex(32)
                        changes[file][key] = value
                elif kind == "secret":
                    if not real(value):
                        value = secrets.token_hex(32)
                        changes[file][key] = value
                    elif key not in (project if file == "project" else dev):
                        changes[file][key] = value
                elif kind != "account" or key not in effective:
                    changes[file][key] = value if real(value) else default
            candidate = effective | changes["project"] | changes["dev"]
            ports = [candidate[key] for key in FIELD_MAP if FIELD_MAP[key][3] == "port"]
            for key in FIELD_MAP:
                if FIELD_MAP[key][3] in ("port", "host", "name"):
                    validate(key, candidate[key])
            if len(ports) != len(set(ports)):
                raise ValueError("Porty baz, Redis, aplikacji i Actuator muszą być różne.")
            if candidate["BANK_APP_PORT"] != effective.get("BANK_APP_PORT", "8080") or not real(effective.get("BANK_API_URL", "")):
                changes["dev"]["BANK_API_URL"] = f"http://localhost:{candidate['BANK_APP_PORT']}"
            if candidate["SHOP_APP_PORT"] != effective.get("SHOP_APP_PORT", "8081") or not real(effective.get("SHOP_PAYMENT_CALLBACK_URL", "")):
                changes["dev"]["SHOP_PAYMENT_CALLBACK_URL"] = f"http://localhost:{candidate['SHOP_APP_PORT']}/payment/callback"
            new = {name: upsert(raw if raw is not None else b"# Konfiguracja lokalna Control Center. Tylko DEV; nie commituj.\n", changes[name]) for name, raw in canonical.items()}
            self.commit(original, new)
            result = self.state()
            result["message"] = "Zapisano konfigurację DEV. Uruchom bazy i Redis, wykonaj buildy, a następnie uruchom lub zrestartuj aplikacje."
            return result

    def stage(self, raw):
        path = None
        try:
            with tempfile.NamedTemporaryFile(dir=self.directory, prefix=".control-config-", delete=False) as file:
                path = Path(file.name)
                path.chmod(0o600)
                file.write(raw)
                file.flush()
                os.fsync(file.fileno())
        except BaseException:
            if path is not None:
                path.unlink(missing_ok=True)
            raise
        return path

    def commit(self, original, new):
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        staged, backups, committed = {}, {}, []
        rollback_ok = True
        try:
            for name in new:
                staged[name] = self.stage(new[name])
                if original[name] is not None:
                    backups[name] = self.stage(original[name])
            if original != self.snapshot():
                raise ConfigurationConflict("Pliki zostały zmienione podczas zapisu. Wczytaj konfigurację ponownie.", "CONFIG_REVISION_CHANGED")
            for name, path in staged.items():
                os.replace(path, self.directory / f"{name}.env")
                committed.append(name)
        except BaseException:
            for name in reversed(committed):
                try:
                    target = self.directory / f"{name}.env"
                    if name in backups:
                        os.replace(backups[name], target)
                    else:
                        target.unlink(missing_ok=True)
                except OSError:
                    rollback_ok = False
            if not rollback_ok:
                raise RuntimeError("Nie udało się wycofać zapisu plików. Zachowano kopie .control-config-* w katalogu env; sprawdź konfigurację ręcznie.") from None
            raise
        finally:
            for path in staged.values():
                try:
                    path.unlink(missing_ok=True)
                except OSError:
                    pass  # Do not hide an original write/rollback failure.
            if rollback_ok:
                for path in backups.values():
                    try:
                        path.unlink(missing_ok=True)
                    except OSError:
                        pass
