"""Fixed generated-artifact scope, never project-wide CLI find/rm or secret files."""
import re
import shutil
import time
from pathlib import Path

REPORTS = ("target/surefire-reports", "target/failsafe-reports", "target/test-results",
           "shop-app/target/surefire-reports", "shop-app/target/failsafe-reports", "shop-app/target/test-results")
PRIVATE = re.compile(r"(?:secret|token|password|vault|credential|\.env|\.p12|\.pem|\.key|\.crt|\.jks|\.sqlite|\.db)", re.I)


def checked(root, path):
    path = Path(path)
    if not path.resolve().is_relative_to(root):
        raise ValueError("Artefakt poza projektem.")
    for node in (path, *path.parents):
        if node == root:
            break
        if node.is_symlink() or (hasattr(node, "is_junction") and node.is_junction()):
            raise ValueError("Dowiązanie artefaktu jest niedozwolone.")
    if path.exists() and path.is_file() and path.stat().st_nlink != 1:
        raise ValueError("Hardlink artefaktu jest niedozwolony.")
    return path


def cleanup(root, job_id, delete=False, retention_days=30, temp_days=7):
    root = Path(root).resolve()
    if not re.fullmatch(r"[0-9a-f]{32}", job_id):
        raise ValueError("Nieprawidłowe ID porządkowania.")
    if type(retention_days) is not int or not 1 <= retention_days <= 3650 or type(temp_days) is not int or not 1 <= temp_days <= 365:
        raise ValueError("Nieprawidłowa retencja artefaktów.")
    now = time.time()
    candidates = {}
    for relative, days in [*((relative, retention_days) for relative in REPORTS), ("tmp", temp_days), (".novabank/logs", retention_days), ("shop-app/.novabank/logs", retention_days)]:
        directory = checked(root, root / relative)
        if not directory.is_dir():
            continue
        for path in directory.rglob("*"):
            if not path.is_file() or PRIVATE.search(str(path.relative_to(directory))) or any(part in (".git", ".codex", ".agents", ".novabank", "control-center") for part in path.relative_to(directory).parts):
                continue
            if relative.endswith("logs") and path.suffix not in (".gz", ".zip"):
                continue  # Never active application log files.
            if not relative.endswith("logs") and path.suffix.lower() not in (".xml", ".txt", ".log", ".html", ".tmp"):
                continue
            checked(root, path)
            if path.stat().st_mtime < now - days * 86400:
                candidates[path] = days
    for directory in (root, root / "shop-app"):
        if directory.is_dir():
            for path in directory.glob("hs_err_pid*.log"):
                checked(root, path)
                if path.is_file() and path.stat().st_mtime < now - retention_days * 86400:
                    candidates[path] = retention_days
    archived = removed = skipped = 0
    archive = root / ".novabank/control-center/web/artifacts-archive" / job_id
    for path, days in candidates.items():
        checked(root, path)
        if not path.is_file() or path.stat().st_mtime >= now - days * 86400:
            skipped += 1
            continue
        if delete:
            path.unlink()
            removed += 1
        else:
            destination = checked(root, archive / path.relative_to(root))
            destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            checked(root, destination)
            if destination.exists():
                raise ValueError("Archiwum już zawiera artefakt; nie nadpisano go.")
            shutil.move(str(path), str(destination))
            destination.chmod(0o600)
            archived += 1
    return f"Archiwizacja: {archived}; trwałe usunięcie: {removed}; pominięte nowe/zmienione: {skipped}. Raporty/rotowane logi: {retention_days} dni, wygenerowane tmp: {temp_days} dni. Zakres katalogów jest stały; źródła, env, certyfikaty, aktywne logi i historia panelu są wyłączone.\n"
