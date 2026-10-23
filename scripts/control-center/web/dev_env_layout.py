"""WSL CLI adapter and guarded mechanical migration of the two DEV env files."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import secrets
import sys

from dev_configuration import DevConfiguration, ConfigurationConflict, assignments
from env_ownership import normalize_pair, APPROVED_DEV_CONFLICTS


def migrate(root, apply=False, prefer_dev=()):
    config = DevConfiguration(root, "local-dev-layout")
    with config.lock:
        original = config.snapshot()
        if any(raw is None for raw in original.values()):
            raise ConfigurationConflict("Migracja wymaga obu istniejących plików DEV; nic nie zapisano.")
        new, summary = normalize_pair(original, prefer_dev)
        summary.update(applied=False, backup=None, valuesDisplayed=False)
        if not apply or new == original:
            return summary
        # Keep byte-identical private originals outside logs/Git. Never overwrite a backup.
        parent = config.directory / "backups"
        if parent.is_symlink() or getattr(parent, "is_junction", lambda: False)() or (parent.exists() and not parent.is_dir()):
            raise ConfigurationConflict("Niedozwolony katalog kopii; nic nie zapisano.")
        parent.mkdir(mode=0o700, exist_ok=True)
        backup = parent / ("dedup-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(4))
        backup.mkdir(mode=0o700)
        for name, raw in original.items():
            descriptor = os.open(backup / f"{name}.env", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(raw)
                stream.flush()
                os.fsync(stream.fileno())
        config.commit(original, new)  # Rechecks both originals; rolls back on write failure.
        actual = config.snapshot()
        if actual != new:
            raise ConfigurationConflict("Pliki zmieniły się po migracji. Zachowano kopię; nie kontynuuj uruchamiania.")
        summary.update(applied=True, backup=backup.relative_to(config.root).as_posix())
        return summary


def main():
    parser = argparse.ArgumentParser(description="DEV layout; secrets are never printed.")
    parser.add_argument("operation", choices=("audit", "migrate", "prepare", "rotate-api-key", "validate", "project-ready"))
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--prefer-dev", action="append", choices=sorted(APPROVED_DEV_CONFLICTS), default=[])
    args = parser.parse_args()
    try:
        if args.prefer_dev and args.operation not in ("audit", "migrate"):
            raise ValueError("Zgoda na konflikt jest dostępna wyłącznie dla audytu/migracji.")
        if args.operation in ("audit", "migrate"):
            print(json.dumps(migrate(args.root, args.operation == "migrate", args.prefer_dev), ensure_ascii=False))
            return 0
        config = DevConfiguration(args.root, "cli-dev-setup")
        if args.operation in ("validate", "project-ready"):
            snapshot = config.snapshot()
            normalized, _ = normalize_pair(snapshot)
            if normalized != snapshot:
                raise ConfigurationConflict("Konfiguracja wymaga migracji do jednego właściciela; nic nie uruchomiono.")
            if args.operation == "project-ready":
                effective = assignments(snapshot["project"] or b"") | assignments(snapshot["dev"] or b"")
                return 0 if all(effective.get(key) for key in ("POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD", "SHOP_POSTGRES_DB", "SHOP_POSTGRES_USER", "SHOP_POSTGRES_PASSWORD")) else 1
            return 0
        config.save(config.state()["revision"], {}, True)
        if args.operation == "rotate-api-key":
            from configuration_files import ConfigurationFiles
            files = ConfigurationFiles(args.root, "cli-api-rotation")
            files.save("dev", "dev", files.state("dev")["revision"], {"BANK_API_KEY": secrets.token_hex(32)}, [], True, True)
        print("Konfiguracja DEV zapisana we właściwych plikach; wartości sekretów nie są wypisywane.")
        return 0
    except (ValueError, ConfigurationConflict, OSError, UnicodeError):
        # Do not emit exception repr/traceback: OS errors or fixtures can contain secrets.
        print("Operacja DEV wstrzymana: konflikt, zmiana plików, nieprawidłowy format lub błąd zapisu. Nie wybieraj wartości automatycznie; sprawdź audyt i chronione kopie.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
