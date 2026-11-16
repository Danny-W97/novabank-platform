"""Non-secret DEV/TEST operator settings and dedicated, owned backup locations."""
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import tempfile
import threading

from dev_configuration import ConfigurationConflict

DEFAULTS = {"dev": {"backupDirectory": ".novabank/backups/dev", "backupRetentionDays": 30,
                    "backupAt": "02:00", "drWeekday": "SUN", "drAt": "04:00",
                    "artifactRetentionDays": 30, "tempRetentionDays": 7},
            "test": {"artifactRetentionDays": 30, "tempRetentionDays": 7}}
CONFIGURABLE = {"artifacts-cleanup", "artifacts-delete", "artifacts-archive"}
MARKER = ".novabank-dev-backup-owner.json"


def regular(path, limit=65536):
    path = Path(path)
    for node in (path, *path.parents):
        if node.is_symlink() or (hasattr(node, "is_junction") and node.is_junction()):
            raise ValueError("Dowiązania ustawień/backupów są niedozwolone.")
    if path.exists() and (not path.is_file() or path.stat().st_nlink != 1 or path.stat().st_size > limit):
        raise ValueError("Wymagany niewielki zwykły plik bez hardlink.")
    return path


def read_file(path, limit=65536):
    path = regular(path, limit)
    return path.read_bytes() if path.exists() else None


def atomic_json(path, data, limit=65536):
    path = regular(path, limit)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    regular(path, limit)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".operator-", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(json.dumps(data, ensure_ascii=False).encode("utf-8"))
            stream.flush()
            os.fsync(stream.fileno())
        temporary.chmod(0o600)
        regular(path, limit)
        os.replace(temporary, path)
        path.chmod(0o600)
    finally:
        if temporary and temporary.exists():
            temporary.unlink()


def backup_path(root, value):
    root = Path(root).resolve()
    if not isinstance(value, str) or not 1 <= len(value) <= 400 or not re.fullmatch(r"[A-Za-z0-9_./ -]+", value) or ".." in Path(value).parts:
        raise ValueError("Katalog kopii: lokalna ścieżka WSL, bez .., znaków shell i dowiązań.")
    path = Path(value)
    if not path.is_absolute():
        path = root / path
    for node in (path, *path.parents):
        if node.is_symlink() or (hasattr(node, "is_junction") and node.is_junction()):
            raise ValueError("Katalog kopii nie może zawierać dowiązań.")
    path = path.resolve()
    base = root / ".novabank/backups"
    internal = path.is_relative_to(base) and path != base
    external = (not path.is_relative_to(root) and root != path and not root.is_relative_to(path)
                and len(path.parts) >= 4 and bool(re.fullmatch(r"novabank-dev-backups(?:-[A-Za-z0-9_-]+)?", path.name)))
    if not (internal or external) or (path.exists() and not path.is_dir()):
        raise ValueError("Wybierz podkatalog .novabank/backups albo osobny lokalny katalog novabank-dev-backups[-nazwa].")
    return path


def ensure_backup_owner(root, value, create=False):
    root = Path(root).resolve()
    path = backup_path(root, value)
    expected = {"format": 1, "project": hashlib.sha256(str(root).encode()).hexdigest()}
    marker = path / MARKER
    raw = read_file(marker)
    if raw is not None:
        try:
            if json.loads(raw) != expected:
                raise ValueError()
        except (ValueError, UnicodeError):
            raise ValueError("Katalog kopii jest przypisany do innego projektu albo ma uszkodzony znacznik.") from None
    elif path.exists() and any(path.iterdir()) and path != root / ".novabank/backups/dev":
        raise ValueError("Istniejący niepusty katalog bez znacznika nie zostanie przejęty. Wybierz nowy pusty katalog; starych kopii nie przeniesiono.")
    if create and raw is None:
        path.mkdir(parents=True, exist_ok=True, mode=0o700)
        regular(marker)
        try:
            descriptor = os.open(marker, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            return ensure_backup_owner(root, value, create=False)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(expected, stream)
            stream.flush()
            os.fsync(stream.fileno())
    return path


def validate(profile, values, root, check_directory=True):
    if not isinstance(profile, str) or profile not in DEFAULTS or not isinstance(values, dict) or set(values) != set(DEFAULTS[profile]):
        raise ValueError("Nieprawidłowy profil lub zestaw ustawień operacyjnych.")
    for key in ("artifactRetentionDays", "tempRetentionDays", *(["backupRetentionDays"] if profile == "dev" else [])):
        maximum = 365 if key == "tempRetentionDays" else 3650
        if type(values[key]) is not int or not 1 <= values[key] <= maximum:
            raise ValueError(f"Pole {key}: dodatnia liczba dni, maksymalnie {maximum}.")
    if profile == "dev":
        for key in ("backupAt", "drAt"):
            if not isinstance(values[key], str) or not re.fullmatch(r"(?:[01][0-9]|2[0-3]):[0-5][0-9]", values[key]):
                raise ValueError("Godzina musi mieć format HH:MM (00:00–23:59).")
        if values["drWeekday"] not in ("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"):
            raise ValueError("Nieprawidłowy dzień tygodnia DR.")
        value = values["backupDirectory"]
        if not isinstance(value, str) or not 1 <= len(value) <= 400 or not re.fullmatch(r"[A-Za-z0-9_./ -]+", value) or ".." in Path(value).parts:
            raise ValueError("Nieprawidłowy zapis katalogu kopii.")
        if check_directory:
            ensure_backup_owner(root, value)
    return dict(values)


class OperatorSettings:
    def __init__(self, root, key="operator-settings", lock=None):
        self.root = Path(root).resolve()
        self.path = self.root / ".novabank/control-center/web/operations.json"
        self.key = key.encode()
        self.lock = lock or threading.RLock()

    def document(self):
        raw = read_file(self.path)
        if raw is None:
            return {p: dict(v) for p, v in DEFAULTS.items()}
        try:
            data = json.loads(raw)
        except (ValueError, UnicodeError):
            raise ValueError("Uszkodzony plik ustawień operacyjnych; nie przywrócono domyślnych automatycznie.") from None
        if not isinstance(data, dict) or set(data) != set(DEFAULTS):
            raise ValueError("Nieprawidłowy format ustawień operacyjnych.")
        # Reading TEST settings must not inspect a possibly unavailable DEV backup drive.
        return {p: validate(p, v, self.root, check_directory=False) for p, v in data.items()}

    def state(self, profile):
        if not isinstance(profile, str) or profile not in DEFAULTS:
            raise ValueError("Ustawienia operacyjne są tylko dla DEV/TEST.")
        with self.lock:
            data = self.document()
            raw = read_file(self.path) or b""
            revision = hmac.new(self.key, raw, hashlib.sha256).hexdigest()
            return {"profile": profile, "revision": revision, "values": data[profile], "exists": self.path.exists()}

    def save(self, profile, revision, values, confirmed):
        if confirmed is not True or not isinstance(revision, str):
            raise ValueError("Zapis wymaga potwierdzenia i rewizji.")
        with self.lock:
            state = self.state(profile)
            if not hmac.compare_digest(revision, state["revision"]):
                raise ConfigurationConflict("Ustawienia zmieniły się. Wczytaj ponownie; nie nadpisano danych.")
            updated = validate(profile, values, self.root)
            data = self.document()
            data[profile] = updated
            atomic_json(self.path, data)
            return self.state(profile)

    def execution(self, profile, action):
        if action not in CONFIGURABLE and not action.startswith(("backup-", "dr-")):
            return {}
        values = self.state(profile)["values"]
        if action.startswith(("backup-", "dr-")):
            validate(profile, values, self.root)
        return values

    def environment(self, values):
        if "backupDirectory" not in values:
            return {}
        values = validate("dev", values, self.root)
        return {"NOVABANK_WEB_BACKUP_DIRECTORY": str(backup_path(self.root, values["backupDirectory"])),
                "NOVABANK_WEB_BACKUP_RETENTION_DAYS": str(values["backupRetentionDays"]),
                "NOVABANK_WEB_BACKUP_AT": values["backupAt"], "NOVABANK_WEB_DR_AT": values["drAt"],
                "NOVABANK_WEB_DR_WEEKDAY": values["drWeekday"]}


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=("check-backup", "prepare-backup"))
    parser.add_argument("root")
    parser.add_argument("directory")
    args = parser.parse_args()
    try:
        print(ensure_backup_owner(args.root, args.directory, create=args.operation == "prepare-backup"))
    except (ValueError, OSError):
        parser.exit(1, "Nie potwierdzono bezpiecznego katalogu kopii DEV. Sprawdź ustawienia i znacznik własności; nie przejęto istniejących kopii.\n")
