"""Test-mode Stripe CLI adapter. Secrets travel via env, never argv or logs."""
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
from urllib.parse import urlsplit

from configuration_files import ConfigurationFiles
from dev_configuration import assignments


def login():
    help_result = subprocess.run(["stripe", "login", "--help"], capture_output=True, timeout=15)
    if b"--non-interactive" not in help_result.stdout:
        command = ["stripe", "login"] + (["--no-browser"] if b"--no-browser" in help_result.stdout else [])
        return subprocess.run(command, input=b"\n").returncode
    result = subprocess.run(["stripe", "login", "--non-interactive"], capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError("Stripe odrzucił rozpoczęcie autoryzacji. Sprawdź wersję CLI i połączenie.")
    data = json.loads(result.stdout)
    browser = data.get("browser_url", "")
    url = urlsplit(browser)
    if url.scheme != "https" or url.hostname not in ("dashboard.stripe.com", "access.stripe.com") or url.username or url.password:
        raise ValueError("Nierozpoznany adres autoryzacji Stripe.")
    # Only this known command shape from Stripe is accepted, never shell execution.
    next_step = shlex.split(data.get("next_step", ""))
    valid = next_step in (["stripe", "login", "--complete-device"], ["stripe", "login", "--complete-reauth"])
    if len(next_step) == 4 and next_step[:3] == ["stripe", "login", "--complete"]:
        poll = urlsplit(next_step[3])
        valid = poll.scheme == "https" and poll.hostname in ("dashboard.stripe.com", "access.stripe.com") and not poll.username and not poll.password
    if not valid:
        raise ValueError("Wersja Stripe zwróciła nieobsługiwany krok autoryzacji. Możesz użyć testowego klucza w dev.env.")
    print("Otwórz lokalnie link autoryzacji Stripe (nie udostępniaj dziennika): " + browser, flush=True)
    print("Po zatwierdzeniu w Stripe panel dokończy połączenie.", flush=True)
    return subprocess.run(next_step, timeout=240).returncode


def run(operation, root=None):
    root = Path(root or Path(__file__).resolve().parents[3])
    files = ConfigurationFiles(root, "stripe-adapter")
    if operation == "login":
        return login()
    with files.lock:
        snapshot = files.snapshot()
        settings = assignments(snapshot["project"] or b"") | assignments(snapshot["dev"] or b"")
    key = settings.get("SHOP_PAYMENT_API_KEY", "")
    if key and not re.fullmatch(r"(?:sk|rk)_test_[A-Za-z0-9_]+", key):
        raise ValueError("Dozwolony jest wyłącznie testowy klucz Stripe sk_test_/rk_test_.")
    if operation == 'cancellation-contract':
        from stripe_contract import contract
        try:
            print(json.dumps(contract(key),ensure_ascii=False))
            print('Zaliczono tylko kontrakt wygaszenia dwóch własnych sesji TEST. Wyścig opłacenia i webhooku wymaga osobnego testu checkoutu.')
            return 0
        except Exception:
            print('Kontrakt Stripe TEST NIEZALICZONY. Odpowiedzi API i klucz pozostają ukryte; sprawdź dostęp i własne zasoby oznaczone ccContractRun.',file=sys.stderr)
            return 1
    environment = dict(os.environ)
    environment.pop("STRIPE_API_KEY", None)
    if key:
        environment["STRIPE_API_KEY"] = key
    # No --live, arbitrary endpoint, remote forwarding or JSON payload logging.
    if operation == "listen":
        if settings.get("SHOP_PAYMENT_PROVIDER") != "stripe":
            raise ValueError("Najpierw ustaw SHOP_PAYMENT_PROVIDER=stripe w dev.env i zrestartuj sklep.")
        port = settings.get("SHOP_APP_PORT", "8081")
        if not re.fullmatch(r"[0-9]{4,5}", port) or not 1024 <= int(port) <= 65535:
            raise ValueError("Nieprawidłowy lokalny port sklepu.")
        return subprocess.call(["stripe", "listen", "--forward-to", f"http://localhost:{port}/api/payments/stripe/webhook"], env=environment)
    if operation != "secret":
        raise ValueError("Nieznana operacja Stripe.")
    result = subprocess.run(["stripe", "listen", "--print-secret"], env=environment, capture_output=True, timeout=90)
    value = result.stdout.decode().strip()
    if result.returncode or not re.fullmatch(r"whsec_[A-Za-z0-9]+", value):
        raise RuntimeError("Nie udało się pobrać sekretu testowego webhooka; szczegóły CLI celowo pominięto.")
    # Expected revision was captured before the network call, not after it.
    files.save("dev", "dev", files.revision(snapshot), {"SHOP_PAYMENT_WEBHOOK_SECRET": value}, [], True, True)
    print("Zapisano testowy sekret webhooka w dev.env. Zrestartuj sklep. Wartość nie jest wypisywana.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(run(sys.argv[1] if len(sys.argv) == 2 else ""))
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError, json.JSONDecodeError):
        print("Nie udało się wykonać operacji Stripe TEST. Sprawdź konfigurację/wersję CLI i autoryzację; sekrety pozostają ukryte.", file=sys.stderr)
        sys.exit(1)
