"""Read-only public certificate diagnostics. Never turns off TLS verification."""
import hashlib
from pathlib import Path
import re
import shutil
import socket
import ssl
import subprocess

from operator_settings import regular
from problems import ProblemError


def diagnose(root, emit):
    root = Path(root).resolve()
    selected = root / ".novabank/tls/vault/vault.crt"
    candidates = [selected, root / "infra/vault/tls/vault.crt", Path("/opt/vault/tls/vault.crt"), Path("/opt/vault/tls/tls.crt")]
    emit("Diagnostyka tylko do odczytu: publiczne certyfikaty i zweryfikowany TLS https://127.0.0.1:8200. Bez tokenu, udziałów unseal, KV, zmiany CA ani restartu.\n")
    openssl = shutil.which("openssl")
    for path in candidates:
        label = str(path.relative_to(root)) if path.is_relative_to(root) else str(path)
        try:
            regular(path, 262144)
            if not path.is_file():
                emit(label + ": brak.\n")
                continue
            if not openssl:
                emit(label + ": obecny; brak OpenSSL do kontroli odcisku/ważności.\n")
                continue
            result = subprocess.run([openssl, "x509", "-in", str(path), "-noout", "-fingerprint", "-sha256", "-enddate"], stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=5)
            match = re.search(r"Fingerprint=([A-Fa-f0-9:]{95})", result.stdout)
            expiry = re.search(r"notAfter=([A-Za-z0-9 :]+ GMT)", result.stdout)
            if result.returncode or not match:
                emit(label + ": niepoprawny/niedostępny certyfikat X.509; treści nie wypisano.\n")
            else:
                emit(label + ": SHA-256 " + match[1].upper() + "; ważność do " + (expiry[1] if expiry else "nie ustalono") + ".\n")
        except (OSError, ValueError, subprocess.TimeoutExpired):
            emit(label + ": brak bezpiecznego odczytu/uprawnień. Nie zmieniono praw.\n")
    systemctl = shutil.which("systemctl")
    if systemctl:
        try:
            result = subprocess.run([systemctl, "is-active", "vault.service"], stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=5)
            value = result.stdout.strip()
            emit("Usługa Vault: " + (value if value in ("active", "inactive", "failed", "activating", "deactivating", "unknown") else "nie ustalono") + ".\n")
        except (OSError, subprocess.TimeoutExpired):
            emit("Nie potwierdzono stanu usługi Vault.\n")
    try:
        regular(selected, 262144)
        context = ssl.create_default_context(cafile=str(selected))
        with socket.create_connection(("127.0.0.1", 8200), timeout=5) as connection:
            # Match the existing VaultClient URL, including its IP identity check.
            with context.wrap_socket(connection, server_hostname="127.0.0.1") as secured:
                fingerprint = hashlib.sha256(secured.getpeercert(binary_form=True)).hexdigest().upper()
                emit("TLS potwierdzony przez skonfigurowane CA i tożsamość 127.0.0.1, zgodnie z adresem klienta Vault Centrali. Certyfikat serwera SHA-256: " + ":".join(fingerprint[i:i + 2] for i in range(0, 64, 2)) + ".\n")
    except (OSError, ValueError):
        emit("Nie potwierdzono TLS przez skonfigurowane CA. Nie pobierano certyfikatu z wyłączoną weryfikacją. Instrukcja: Dokumentacja → Rozwiązywanie problemów → vault-ca. Porównaj publiczne odciski i konfigurację listenera administracyjnie; nie podmieniaj CA na ślepo.\n")
        raise ProblemError("VAULT_CA_NOT_READY") from None
    emit("Kontrola zakończona. Poprawny TLS nie potwierdza gotowości AppRole, KV, baz ani działania aplikacji.\n")
