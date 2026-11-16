#!/usr/bin/env python3
"""Local operator UI: named DEV/TEST and explicitly confirmed PROD-like actions."""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import hmac
import json
import os
import re
import secrets
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import uuid
import webbrowser
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from dev_cash import DevCash, validate_cash
from dev_accounts import DevAccounts, filters as account_filters, account_id as validate_account_id
from dev_shop_accounts import DevShopAccounts, DevShopDatabase, filters as shop_account_filters
from payment_cases import PaymentCases, filters as payment_case_filters
from notifications import Notifications
from notification_retention import NotificationRetention
from maintenance_commands import ACTIONS as MAINTENANCE_ACTIONS, validate as validate_maintenance, command as maintenance_command, environment as maintenance_environment
from openapi import document as openapi_document
from dev_configuration import ConfigurationConflict, DevConfiguration
from configuration_files import ConfigurationFiles
from local_operations import EXTENDED_ACTIONS
from production_catalog import PRODUCTION_ACTIONS
from production_operations import ProductionOperations, PARAMETERS as PRODUCTION_PARAMETERS, validate_parameters as validate_production
from maintenance_operations import cleanup as artifact_cleanup
from problems import ProblemError, metadata as problem_metadata
from problem_support import ProblemSupport, REPAIR_ACTION
from operator_settings import OperatorSettings
from legacy_history import LegacyHistory
from legacy_schedules import LegacySchedules
from totp_recovery import TotpRecovery, ACTIONS as RECOVERY_ACTIONS

WEB_ROOT = Path(__file__).resolve().parent
PROJECT_ROOT = WEB_ROOT.parents[2]
MAX_LOG = 256_000
SECRET_NAME = re.compile(r"password|secret|token|api.?key|encryption.?key|private.?key", re.I)


@dataclass(frozen=True)
class Action:
    id: str
    title: str
    group: str
    description: str
    profiles: tuple[str, ...] = ("dev",)
    changes: bool = False
    timeout: int = 120


ACTIONS = (
    Action("audit-release-check", "Gotowość wydania z Git", "Bezpieczeństwo", "Odczyt kompletności publicznych źródeł względem HEAD. Wskazuje brakujące migracje, nieśledzone i zmienione pliki. Bez git add, commitu, pusha i ujawniania sekretów. Wynik NOT_READY wymaga przeglądu operatora; nie zastępuje zielonego CI.", ("dev","test")),
    Action("audit-payment-transport", "Awarie HTTP: rzeczywisty bank–sklep", "Testy", "Buduje izolowany eksport obu aplikacji i sprawdza utratę odpowiedzi po rzeczywistym obciążeniu banku. Nowy klucz jest blokowany; uzgodnienie odzyskuje wynik. Własne bazy i Redis, bez istniejącego DEV i Stripe.", ("dev","test"), True, 1800),
    Action("payment-cases", "Uzgadnianie płatności historycznych", "Bezpieczeństwo", "Lista spraw, dowody banku/Stripe, zwroty, konflikty i audytowane decyzje DEV. Zamknięcie sprawy nie oznacza odmowy płatności."),
    Action("payment-case-plan", "Sprawdź dowody sprawy płatności", "Bezpieczeństwo", "Odczyt dowodów bez nowego obciążenia. Zapisuje plan i zdarzenie audytowe; wymaga V15 i sprawdzonej pary kopii DEV.", ("dev",), True, 900),
    Action("payment-case-apply", "Zastosuj potwierdzony wynik sprawy", "Bezpieczeństwo", "Tylko dowód PAID, pełnego zgodnego zwrotu lub trwałej bankowej odmowy. RECONCILE, kopie i zatrzymane zapisy. Brak ręcznego wymuszania wyniku finansowego.", ("dev",), True, 900),
    Action("payment-case-decision", "Decyzja operatora w sprawie płatności", "Bezpieczeństwo", "Eskalacja, zamknięcie bez rozstrzygnięcia lub zgoda/cofnięcie zgody na niezależny nowy zakup. DECIDE i uzasadnienie. Nie zmienia statusu płatności, sald ani koszyka.", ("dev",), True, 900),
    Action("purchase-cancel", "Anuluj zakup i zamknij sprawę DEV", "Bezpieczeństwo", "Sprawdza brak obciążenia i może zakończyć oczekującą płatność u operatora. Dopiero potwierdzony wynik zapisuje CANCELLED i zamyka sprawę w Historii. UNKNOWN wymaga wyjaśnienia. Zachowuje produkty i historię; wymaga V17 sklepu.", ("dev",), True, 180),
    Action("purchase-refresh", "Sprawdź stan bieżącego zakupu DEV", "Bezpieczeństwo", "Odczyt operatora i uzgodnienie wyniku istniejącej próby, bez nowego obciążenia ani anulowania. Wymaga aktualnych aplikacji i V16 sklepu.", ("dev",), True, 180),
    Action("payment-history-migration-plan", "Migracja historycznych płatności — plan", "Bezpieczeństwo", "Lokalny DEV, aktualny JAR banku i schema sklepu V14. Sprawdza sumy najnowszej kompletnej pary kopii. Dopasowuje wyłącznie jednoznaczne sukcesy banku/kart lub opłacone sesje Stripe TEST w PLN. Brak dowodów, odmowy i konflikty pozostają zablokowane. Bez zapisu historii.", ("dev",), False, 900),
    Action("payment-history-migration-apply", "Migracja historycznych płatności — wykonaj", "Bezpieczeństwo", "Wyłącznie z przejrzanym planDigest, odtworzonymi kopiami i zatrzymanymi zapisami banku/sklepu oraz listenerem. MIGRATE. Zachowuje zamówienia, zwroty, salda i koszyki. Utrwala tylko potwierdzone sukcesy; journal umożliwia wznowienie po przerwaniu. Nie jest globalną transakcją obu baz ani automatem rozstrzygania FAILED.", ("dev",), True, 900),
    Action("audit-load-transfers", "Obciążenie: równoległe przelewy HTTP", "Testy", "Izolowany DEV/TEST: 16 klientów przez 120 s, p95 ≤ 2 s, p99 ≤ 5 s, zero błędów i naruszeń księgowania. Replay i scheduler, własne PostgreSQL/Redis. Raport: czasy, JVM, host widoczny dla WSL/CI, docker stats obu kontenerów, pula i blokady DB. Nie jest certyfikacją produkcji.", ("dev", "test"), True, 1800),
    Action("audit-security-http", "Bezpieczeństwo: sesje, 2FA i dostęp HTTP", "Testy", "Dwie izolowane JVM ze wspólnym testowym Redis/PostgreSQL. Rzeczywiste cookie, CSRF, role, równoległe wyzwania 2FA i drafty. Nie testuje docelowego firewalla; K5/K6 pozostają TODO.", ("dev", "test"), True, 1800),
    Action("audit-security-k4", "K4 — kontrola uprawnień i CSRF", "Bezpieczeństwo", "Sprawdza rejestr operacji banku i sklepu, role, właścicieli danych, CSRF, klucze API, callbacki i podpisy Stripe. Izolowane bazy i sesje; raport z liczbą sprawdzonych operacji i brakujących dowodów. K5/K6 poza zakresem.", ("dev", "test"), True, 3600),
    Action("audit-totp-restore-login", "2FA: kopia, rotacja i logowanie HTTP", "Testy", "Syntetyczna kopia PostgreSQL, odtworzenie do osobnego kontenera, rotacja zachowująca sekret i logowanie do nowej JVM z keyringiem. Bez kopii i kluczy operatora. Wymaga budowy JAR w fazie verify.", ("dev", "test"), True, 1800),
    Action("audit-payment-bank", "Awarie płatności: bank", "Testy", "Izolowane testy transakcji, utraty potwierdzenia, współbieżności i replay. Nie stanowi pełnego pokrycia wszystkich wariantów P01–P16 ani testu rzeczywistych środków.", ("dev", "test"), True, 1800),
    Action("audit-payment-shop", "Awarie płatności: sklep", "Testy", "Izolowane testy trwałych prób, podpisanych webhooków, błędnych danych i równoległych operatorów. Bez połączeń do konta Stripe TEST i bez zmiany historii DEV.", ("dev", "test"), True, 1800),
    Action("backup-totp-inspect", "Test odzyskania 2FA — 1/2: wykrycie kluczy", "Backup DEV", "Dwa etapy: odtworzenie najnowszej kompletnej kopii banku i wykrycie kluczy, potem formularz kluczy i weryfikacja wszystkich szyfrogramów. Wymaga lokalnego obrazu postgres:18, JDK 25 i aktualnego JAR. Tylko zaufana kopia DEV do 512 MiB; bez portów, rotacji, logowania HTTP i zmian env. Każdy etap usuwa swój kontener.", ("dev",), True, 900),
    Action("backup-totp-verify", "Test odzyskania 2FA — 2/2: odszyfrowanie", "Backup DEV", "Wyłącznie z aktualnego planu testu odzyskania 2FA. Klucze tylko w pamięci i stdin weryfikatora; bez zapisu w historii, env i logach. Nie jest testem logowania.", ("dev",), True, 900),
    Action("payment-history-review", "Przegląd historycznych płatności", "Bezpieczeństwo", "Odczyt liczników nieprzypisanych i sprzecznych płatności banku. Wymaga schematu po migracjach i aktualnego JAR. Wynik STOP wymaga mapy i uzgodnienia ze sklepem/operatorem; narzędzie niczego nie przypisuje i nie wykonuje Flyway.", ("dev", "test")),
    Action("totp-rotation-plan", "Plan rotacji szyfrowania TOTP", "Bezpieczeństwo", "Sprawdza jedną partię istniejących sekretów bez zmiany danych. Wymaga skonfigurowanego keyringu i aktualnego JAR banku. Nie uruchamia Flyway ani aplikacji.", ("dev", "test")),
    Action("totp-rotation-apply", "Wykonaj zatwierdzoną partię TOTP", "Bezpieczeństwo", "Ponowne szyfrowanie tego samego sekretu. STOP, jeśli nie sprawdzono kopii lub wszystkie działające instancje nie wczytały nowego keyringu razem ze starymi kluczami. Edycja env nie przeładowuje aplikacji. Wymaga aktualnego planu i ROTATE. Checkpoint i wynik zapisują się razem; ponowienie planu jest bezskutkowe. Panel nie restartuje usług.", ("dev", "test"), True),
    Action("environment-check", "Sprawdź środowisko", "Diagnostyka", "Narzędzia i obecność plików, bez wyświetlania sekretów.", ("dev", "test", "prod")),
    Action("prod-check", "Sprawdź pliki PROD", "Diagnostyka", "Tylko obecność lokalnych plików bootstrap i TLS. Nie potwierdza gotowości zdalnej produkcji.", ("prod",)),
    Action("dev-env", "Przygotuj konfigurację DEV", "Środowisko", "Tworzy brakujące project.env/dev.env i generuje brakujące sekrety. Dane uzupełnisz w Konfiguracja DEV. Nie uruchamia baz ani aplikacji.", changes=True),
    Action(REPAIR_ACTION, "Uzgodnij monitoring z DEV", "Pomoc i naprawy", "Wyłącznie z zatwierdzonego planu powiadomienia: istniejące dane metryk DEV do konfiguracji monitoringu, z kopią i kontrolą rewizji. Bez rotacji i restartów.", changes=True),
    Action("apps-start", "Uruchom aplikacje DEV", "Środowisko", "Bank i sklep z aktualnych JAR-ów. Wymaga buildów i konfiguracji DEV; nie zatrzymuje aplikacji z IDE.", changes=True),
    Action("apps-stop", "Zatrzymaj aplikacje panelu", "Środowisko", "Tylko procesy DEV uruchomione przez tę instancję panelu. Nie zatrzymuje IDE ani baz.", changes=True),
    Action("apps-logs", "Logi aplikacji panelu", "Diagnostyka", "Ostatnie maskowane logi banku i sklepu uruchomionych przez panel. Bez logów aplikacji z IDE."),
    Action("cash-credit", "Dodaj środki do konta", "Salda DEV", "Korekta testowego salda aktywnego konta PLN. Wybierz konto i kwotę; operacja jest audytowana.", changes=True),
    Action("cash-debit", "Odejmij środki z konta", "Salda DEV", "Korekta testowego salda PLN, bez możliwości zejścia poniżej zera. Wyłącznie lokalna baza DEV.", changes=True),
    Action("infra-status", "Status infrastruktury", "Docker", "Compose, healthchecki, PostgreSQL i Redis."),
    Action("infra-up", "Uruchom PostgreSQL i Redis", "Docker", "Uruchamia tylko lokalne kontenery baz i Redis z project.env; nie usuwa danych.", changes=True, timeout=600),
    Action("infra-restart", "Restart baz i Redis", "Docker", "Przerywa połączenia lokalnych aplikacji. Wolumeny pozostają.", changes=True, timeout=600),
    Action("infra-stop", "Zatrzymaj bazy i Redis", "Docker", "Zatrzymuje lokalne kontenery, bez usuwania wolumenów.", changes=True),
    Action("compose-check", "Sprawdź Compose", "Diagnostyka", "Walidacja konfiguracji bez wypisywania jej wartości."),
    Action("redis-status", "Status sesji Redis", "Diagnostyka", "PING i liczba kluczy sesji. Bez odczytu danych użytkowników i bez kasowania."),
    Action("flyway-status", "Historia migracji", "Diagnostyka", "Podsumowanie flyway_schema_history, bez repair i bez migracji."),
    Action("db-logs", "Logi infrastruktury", "Diagnostyka", "Ostatnie 120 linii kontenerów. Dane wrażliwe są maskowane."),
    Action("build-bank", "Buduj bank", "Build", "Maven clean package z profilem unit-tests. Aktualizuje target/.", ("dev", "test"), True, 1800),
    Action("build-shop", "Buduj sklep", "Build", "Maven clean package dla shop-app, z testami jednostkowymi.", ("dev", "test"), True, 1800),
    Action("unit-bank", "Testy banku", "Testy", "Maven -Punit-tests test.", ("dev", "test"), True, 1800),
    Action("unit-shop", "Testy sklepu", "Testy", "Maven -Punit-tests test dla shop-app.", ("dev", "test"), True, 1800),
    Action("unit-all", "Wszystkie testy jednostkowe", "Testy", "Kolejno bank i sklep; przerwanie po błędzie.", ("dev", "test"), True, 1800),
    Action("it-bank", "Integracyjne: bank", "Testy", "Maven verify + Testcontainers. Wymaga Docker; tworzy tymczasowe kontenery.", ("dev", "test"), True, 1800),
    Action("it-shop", "Integracyjne: sklep", "Testy", "Maven verify + Testcontainers dla shop-app. Wymaga Docker.", ("dev", "test"), True, 1800),
)
ACTIONS = ACTIONS + tuple(Action(*item) for item in EXTENDED_ACTIONS)
ACTIONS = ACTIONS + tuple(Action(*item) for item in PRODUCTION_ACTIONS)
ACTION_MAP = {action.id: action for action in ACTIONS}
MAVEN_ARGS = {
    "build-bank": ["-Punit-tests", "clean", "package"],
    "build-shop": ["-f", "shop-app/pom.xml", "-Punit-tests", "clean", "package"],
    "unit-bank": ["-Punit-tests", "test"],
    "unit-shop": ["-f", "shop-app/pom.xml", "-Punit-tests", "test"],
    "it-bank": ["-Pintegration-tests", "verify"],
    "it-shop": ["-f", "shop-app/pom.xml", "-Pintegration-tests", "verify"],
    "build-prod-bank": ["-Pprod", "-DskipTests", "clean", "package"],
    "build-prod-shop": ["-f", "shop-app/pom.xml", "-Pprod", "-DskipTests", "clean", "package"],
}
DOCUMENTS = {"guide": "docs/General_Instruction.md", "panel": "docs/General_Instruction.pl.md"}


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def env_values(path: Path) -> dict[str, str]:
    """Read dotenv/properties assignments as data, NEVER source/eval them."""
    if not path.is_file():
        return {}
    values = {}
    for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
        key, sep, value = line.partition("=")
        if sep and re.fullmatch(r"[A-Z][A-Z0-9_]*", key.strip()):
            values[key.strip()] = value.strip().strip("\"'")
    return values


def redact(text: str, values: tuple[str, ...] = ()) -> str:
    text = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", text)
    for value in sorted(set(values), key=len, reverse=True):
        if len(value) >= 4:
            text = text.replace(value, "***")
    text = re.sub(r"(?i)(?<![\w.-])([\w.-]{0,128}(?:password|secret|token|api[_-]?key|encryption[_-]?key)[\w.-]{0,128}\s*[:=]\s*)(\"[^\"]*\"|'[^']*'|[^\s,;]+)", r"\1***", text)
    text = re.sub(r"(?i)(Authorization\s*[:=]\s*)(?:Bearer|Basic)\s+\S+", r"\1***", text)
    text = re.sub(r"(?i)\b(?:sk_(?:live|test)_|whsec_|hvs\.)[A-Za-z0-9_.-]+", "***", text)
    text = re.sub(r"(://[^\s/:@]+:)[^\s@]+@", r"\1***@", text)
    text = re.sub(r"(?<![\w.+-])[\w.+-]{1,64}@[\w.-]{1,253}\.[A-Za-z]{2,}", "[email ukryty]", text)
    return re.sub(r"(?<!\d)(?:PL)?\d{26}(?!\d)", "[rachunek ukryty]", text)


def java_executable() -> str | None:
    suffix = "java.exe" if os.name == "nt" else "java"
    if os.environ.get("JAVA_HOME"):
        candidate = Path(os.environ["JAVA_HOME"]) / "bin" / suffix
        if candidate.is_file():
            return str(candidate)
    if os.name == "nt":
        # Prefer a real JDK over the WindowsApps/Oracle launcher aliases.
        base = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Java"
        for home in sorted(base.glob("jdk-25*"), reverse=True):
            candidate = home / "bin" / suffix
            if candidate.is_file():
                return str(candidate)
    return shutil.which("java")


def clean_environment() -> dict[str, str]:
    prefixes = ("SPRING_", "BANK_", "SHOP_", "POSTGRES_", "REDIS_", "MAIL_", "EMAIL_", "MAILPIT_", "VAULT_", "TOTP_", "SERVER_", "MANAGEMENT_", "TEST_")
    removed = {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "MAVEN_ARGS", "MAVEN_OPTS",
               "BASH_ENV", "ENV", "ENV_FILE", "BACKUP_DIR", "BANK_DUMP", "SHOP_DUMP", "NOVABANK_WEB_MODE",
               "DEFAULT_BACKUP_DIR", "BACKUP_DELETE_OLD", "BACKUP_RETENTION_DAYS", "NOVABANK_WEB_TIMER_DRY_RUN", "NOVABANK_WEB_MIGRATION_ID"}
    return {key: value for key, value in os.environ.items() if not key.startswith(prefixes) and key not in removed}


def process_options() -> dict:
    if os.name == "nt":
        return {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW}
    return {"start_new_session": True}


def safe_output(stream, emit, secret_values: tuple[str, ...]) -> None:
    """Redact complete lines, so secrets split across pipe chunks cannot leak."""
    in_private_key = False
    dropping_long_line = False
    while data := stream.readline(16_001):
        line = data.decode("utf-8", errors="replace")
        if dropping_long_line:
            dropping_long_line = not line.endswith("\n")
            continue
        if len(data) > 16_000:
            emit("[Zbyt długa linia została pominięta.]\n")
            dropping_long_line = not line.endswith("\n")
            continue
        if re.search(r"-----BEGIN .*PRIVATE KEY-----", line):
            in_private_key = True
            emit("[Klucz prywatny ukryty.]\n")
        elif in_private_key:
            if re.search(r"-----END .*PRIVATE KEY-----", line):
                in_private_key = False
        else:
            emit(redact(line, secret_values))


def stop_process(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill.exe", "/PID", str(process.pid), "/T", "/F"], capture_output=True, timeout=10, check=False)
    else:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        raise RuntimeError("Proces nie zakończył się po próbie zatrzymania.")


class BusyError(Exception):
    pass


class Panel:
    def __init__(self, root: Path = PROJECT_ROOT):
        self.root = root.resolve()
        self.runtime = self.root / ".novabank/control-center/web"
        self.token = secrets.token_urlsafe(32)
        self.dev_configuration = DevConfiguration(self.root, self.token)
        self.configuration_files = ConfigurationFiles(self.root, self.token, self.dev_configuration.lock)
        self.operation_settings = OperatorSettings(self.root, self.token, self.dev_configuration.lock)
        self.legacy_schedules = LegacySchedules(self.root, self.operation_settings, self.token)
        self.cli_history = LegacyHistory(self.root, lambda text: redact(text, self.known_secrets()), self.dev_configuration.lock)
        self.job_settings = {}
        self.production = ProductionOperations(self.root, self.token, self.dev_configuration.lock)
        self.totp_recovery = TotpRecovery(self.root, self.operation_settings)
        self.private_parameters = {}
        self.lock = threading.RLock()
        self.account_reads = threading.BoundedSemaphore(2)
        self.jobs: dict[str, dict] = {}
        self.processes: dict[str, subprocess.Popen] = {}
        self.apps: dict[str, subprocess.Popen] = {}
        self.app_readers: list[threading.Thread] = []
        self.cancelled: set[str] = set()
        self.closing = False
        self.threads: list[threading.Thread] = []
        self.notifications = Notifications(self.root, self.runtime, lambda text: redact(text, self.known_secrets()))
        self.notification_retention = NotificationRetention(self.notifications)
        self.support = ProblemSupport(self)
        self.notification_stop = threading.Event()
        self.notification_thread = None

    def start_notifications(self) -> None:
        def collect():
            while not self.notification_stop.is_set():
                try:
                    self.notifications.scan()
                except (OSError, RuntimeError, EOFError, sqlite3.Error):
                    self.notify("Control Center", "WARN", "Nie udało się zebrać logów DEV. Sprawdź pliki logów i ich uprawnienia.", "notification-collector-error")
                self.notification_stop.wait(2)
        self.notification_thread = threading.Thread(target=collect, daemon=True)
        self.notification_thread.start()

    def cash(self) -> DevCash:
        settings = env_values(self.root / ".novabank/env/project.env") | env_values(self.root / ".novabank/env/dev.env")
        return DevCash(self.root, settings, clean_environment())

    def shop_accounts(self) -> DevShopAccounts:
        settings = env_values(self.root / ".novabank/env/project.env") | env_values(self.root / ".novabank/env/dev.env")
        return DevShopAccounts(DevShopDatabase(self.root, settings, clean_environment()))

    def payment_cases(self):
        settings = env_values(self.root / ".novabank/env/project.env") | env_values(self.root / ".novabank/env/dev.env")
        return PaymentCases(DevShopDatabase(self.root,settings,clean_environment()))

    def notify(self, source: str, level: str, message: str, fingerprint: str, **problem):
        try:
            return self.notifications.add(source, level, message, fingerprint=fingerprint, **problem)
        except (OSError, RuntimeError, sqlite3.Error):
            # A log collector failure must not alter the result of a financial operation.
            print("WARN: nie udało się zapisać powiadomienia panelu.", file=sys.stderr, flush=True)

    def notify_problem(self, code, profile=None, job_id=None, context=None):
        guide = problem_metadata(code)
        job_id = job_id if isinstance(job_id, str) and re.fullmatch(r"[0-9a-f]{32}", job_id) else None
        return self.notify("Control Center", "ERROR", guide["title"], f"problem:{job_id or uuid.uuid4().hex}:{guide['code']}",
                           issue_code=guide["code"], profile=profile if profile in ("dev", "test", "prod") else None,
                           job_id=job_id, context=context)

    def known_secrets(self) -> tuple[str, ...]:
        values = [value for key, value in os.environ.items() if SECRET_NAME.search(key)]
        paths = [*(self.root / ".novabank/env").glob("*.env"), self.root / ".novabank/monitoring/.env.monitoring"]
        for path in paths:
            try:
                if path.stat().st_size <= 1_000_000:
                    values.extend(value for key, value in env_values(path).items() if SECRET_NAME.search(key))
            except OSError:
                # Diagnostics do not require permission to read secret values.
                continue
        return tuple(values)

    def append(self, job_id: str, text: str, secret_values: tuple[str, ...] = ()) -> None:
        safe = redact(text[:16_000], secret_values)
        for line in safe.splitlines():
            if re.search(r"(?:^|\s|\[)(WARN(?:ING)?|ERROR)(?:\s|\]|:)|^Błąd:", line[:250], re.I):
                level = "WARN" if re.search(r"\bWARN(?:ING)?\b", line[:250], re.I) else "ERROR"
                self.notify("Control Center", level, line, f"job:{job_id}:{line}",
                            profile=self.jobs.get(job_id, {}).get("profile"), job_id=job_id if re.fullmatch(r"[0-9a-f]{32}", job_id) else None)
        with self.lock:
            job = self.jobs[job_id]
            remaining = MAX_LOG - len(job["log"])
            if remaining > 0:
                job["log"] += safe[:remaining]
                if len(safe) >= remaining:
                    job["log"] += "\n[Limit logu; dalszy wynik pominięto.]\n"

    def public_jobs(self) -> list[dict]:
        with self.lock:
            return [{key: value for key, value in job.items() if key != "log"} for job in reversed(list(self.jobs.values()))]

    def submit(self, action_id: str, profile: str, confirmed: bool, parameters=None, production_confirmation=None) -> dict:
        action = ACTION_MAP.get(action_id)
        if not action or profile not in action.profiles:
            raise ValueError("Operacja niedozwolona dla wybranego profilu.")
        if profile == "prod" and (confirmed is not True or production_confirmation != "PROD"):
            raise ValueError("Operacja PROD wymaga potwierdzenia i wpisania PROD. Może dotyczyć Vault i danych produkcyjnych.")
        if profile != "prod" and production_confirmation is not None:
            raise ValueError("Potwierdzenie PROD nie jest dozwolone w DEV/TEST.")
        if action.changes and confirmed is not True:
            raise ValueError("Operacja wymaga świadomego potwierdzenia.")
        private = None
        if action_id in RECOVERY_ACTIONS:
            private = self.totp_recovery.parameters(action_id, profile, parameters)
            parameters = None
        elif action_id in MAINTENANCE_ACTIONS:
            private = validate_maintenance(action_id, profile, parameters)
            parameters = None
        elif action_id in PRODUCTION_PARAMETERS:
            private = validate_production(action_id, parameters)
            parameters = None
        elif action_id == REPAIR_ACTION:
            private = self.support.validate_plan(parameters)
            parameters = None
        elif action_id in ("migrate-cli-backup", "migrate-cli-dr"):
            if not isinstance(parameters, dict) or set(parameters) != {"revision"} or not isinstance(parameters["revision"], str) or not re.fullmatch(r"[a-f0-9]{64}", parameters["revision"]):
                raise ValueError("Migracja wymaga aktualnej rewizji planu.")
            private = dict(parameters)
            parameters = None
        elif action_id == "artifacts-delete":
            if parameters != {"deleteConfirmation": "USUN"}:
                raise ValueError("Trwałe usunięcie wygasłych artefaktów wymaga wpisania USUN.")
            parameters = None
        elif action_id in ("cash-credit", "cash-debit"):
            parameters = validate_cash(parameters)
        elif parameters is not None:
            raise ValueError("Ta operacja nie przyjmuje parametrów.")
        with self.lock:
            if action_id == REPAIR_ACTION:
                # Durable idempotency also covers a lost HTTP response and a restarted panel.
                request = private
                with self.notifications.lock, self.notifications.connect() as database:
                    old = database.execute("SELECT * FROM repair_requests WHERE request_id = ?", (request["requestId"],)).fetchone()
                if old:
                    if old["notification_id"] != request["notificationId"] or old["revision"] != request["revision"]:
                        raise ValueError("Identyfikator naprawy dotyczy innych danych.")
                    if old["job_id"] in self.jobs:
                        return {key: value for key, value in self.jobs[old["job_id"]].items() if key != "log"}
                    raise ConfigurationConflict("Ta próba została już zarejestrowana. Sprawdź log/kopię; nie wykonano ponownie naprawy.")
            if self.closing or any(job["status"] == "running" for job in self.jobs.values()):
                raise BusyError("Jedno zadanie na raz. Poczekaj na koniec bieżącej operacji.")
            while len(self.jobs) >= 60:
                self.jobs.pop(next(iter(self.jobs)))
            job_id = uuid.uuid4().hex
            execution_settings = self.operation_settings.execution(profile, action_id)
            if action_id == REPAIR_ACTION:
                old = self.notifications.reserve_repair(private["requestId"], private["notificationId"], private["revision"], job_id)
                if old:
                    raise ConfigurationConflict("Próba naprawy została już zarejestrowana przez inny proces.")
            self.job_settings[job_id] = execution_settings
            job = {"id": job_id, "action": action.id, "title": action.title, "profile": profile,
                   "status": "running", "started": now(), "finished": None, "exitCode": None, "log": ""}
            if parameters is not None:
                job["parameters"] = parameters
            self.jobs[job_id] = job
            if private is not None:
                self.private_parameters[job_id] = private
            thread = threading.Thread(target=self.execute, args=(job_id, action, profile), daemon=action_id not in RECOVERY_ACTIONS and action_id not in PRODUCTION_PARAMETERS and action_id not in (REPAIR_ACTION, "migrate-cli-backup", "migrate-cli-dr", "backup-schedule-install", "dr-schedule-install", "backup-schedule-run-now", "dr-schedule-run-now"))
            self.threads = [item for item in self.threads if item.is_alive()]
            self.threads.append(thread)
            try:
                thread.start()
            except Exception:
                if action_id == REPAIR_ACTION:
                    self.notifications.release_repair(private["requestId"])
                self.jobs.pop(job_id, None)
                self.private_parameters.pop(job_id, {}).clear()
                self.job_settings.pop(job_id, None)
                self.threads.remove(thread)
                raise RuntimeError("Nie udało się uruchomić zadania. Parametry usunięto z kolejki; operacja nie wystartowała.") from None
            return {key: value for key, value in job.items() if key != "log"}

    def command(self, action_id: str, profile: str) -> list[str]:
        action = ACTION_MAP.get(action_id)
        if not action or profile not in action.profiles or (profile == "prod" and action_id not in MAVEN_ARGS and action_id != "install-vault"):
            raise ValueError("Polecenie nie jest dozwolone dla tego profilu.")
        if action_id == 'audit-release-check':
            return [sys.executable,str(self.root/'scripts/ci/release_check.py')]
        audit_checks={'audit-load-transfers':'load','audit-payment-transport':'transport','audit-security-http':'security','audit-security-k4':'k4',
                      'audit-payment-bank':'payment-bank','audit-payment-shop':'payment-shop','audit-totp-restore-login':'totp'}
        if action_id in audit_checks:
            return [sys.executable,str(self.root/'scripts/ci/audit_extended_verify.py'),audit_checks[action_id]]
        if os.name == "nt" and action_id in MAVEN_ARGS:
            return [str(self.root / "mvnw.cmd"), *MAVEN_ARGS[action_id]]
        relative = "scripts/control-center/web-prod-operations.sh" if action_id == "install-vault" else "scripts/control-center/control-center.sh"
        arguments = [action_id, profile] if action_id == "install-vault" else ["--web-action", action_id, profile]
        if os.name == "nt":
            wsl = shutil.which("wsl.exe")
            if not wsl:
                raise RuntimeError("Ta operacja wymaga WSL z Bash i Docker.")
            drive = self.root.drive.rstrip(":").lower()
            if len(drive) != 1:
                raise RuntimeError("Projekt musi znajdować się na lokalnym dysku Windows, nie UNC.")
            wsl_root = "/mnt/" + drive + self.root.as_posix()[2:]
            return [wsl, "--cd", wsl_root, "bash", relative, *arguments]
        if action_id in MAVEN_ARGS:
            return ["bash", str(self.root / "mvnw"), *MAVEN_ARGS[action_id]]
        return ["bash", str(self.root / relative), *arguments]

    def run_command(self, job_id: str, command: list[str], deadline: float, secret_values: tuple[str, ...]) -> int:
        if job_id in self.cancelled:
            return 130
        if self.jobs[job_id]["profile"] != "prod":
            self.configuration_files.snapshot()  # Refuse symlink/junction inputs before local operations.
        environment = clean_environment()
        maintenance = self.jobs[job_id].get("action") in MAINTENANCE_ACTIONS
        if not maintenance:
            environment.update(self.operation_settings.environment(self.job_settings.get(job_id, {})))
        if java_executable():
            java_home = str(Path(java_executable()).parent.parent)
            environment["JAVA_HOME"] = java_home
            environment["PATH"] = str(Path(java_home) / "bin") + os.pathsep + environment.get("PATH", "")
        if maintenance:
            environment.update(maintenance_environment(self, self.jobs[job_id]["profile"], self.jobs[job_id].get("action")))
        elif self.jobs[job_id].get("action", "").startswith("audit-"):
            # Testcontainers owns every connection/fixture; never inject operator TEST/DEV keys.
            environment = {key: value for key, value in environment.items() if not key.startswith("STRIPE_")}
        elif self.jobs[job_id]["profile"] == "test":
            environment.update(self.configuration_files.test_environment())
        process = subprocess.Popen(command, cwd=self.root, env=environment, stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **process_options())
        with self.lock:
            self.processes[job_id] = process
            cancel_now = job_id in self.cancelled
        if cancel_now:
            stop_process(process)

        def drain() -> None:
            assert process.stdout
            safe_output(process.stdout, lambda line: self.append(job_id, line), secret_values)
            process.stdout.close()

        reader = threading.Thread(target=drain, daemon=True)
        reader.start()
        try:
            code = process.wait(timeout=max(0.1, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            stop_process(process)
            self.append(job_id, "\n[Timeout — zatrzymano proces zadania.]\n")
            code = 124
        finally:
            reader.join(timeout=10)
            with self.lock:
                self.processes.pop(job_id, None)
        return code

    def configuration_report(self, profile: str) -> str:
        lines = [f"Profil widoku: {profile.upper()}", "Sekrety nie są wypisywane."]
        tools = {"Python": sys.executable, "Java": java_executable(), "Bash": shutil.which("bash"),
                 "Docker (system panelu)": shutil.which("docker"), "WSL (Windows)": shutil.which("wsl.exe")}
        lines.extend(f"{name}: {'wykryto' if binary else 'brak w systemie panelu'}" for name, binary in tools.items())
        paths = [".novabank/env/project.env", ".novabank/env/dev.env"]
        if profile == "prod":
            paths = [".novabank/env/prod.bank.env", ".novabank/env/prod.shop.env", ".novabank/tls/vault/vault.crt",
                     ".novabank/tls/vault/vault-truststore.p12", ".novabank/tls/prod/bank-server.p12", ".novabank/tls/prod/shop-server.p12"]
            lines.append("PROD: wyłącznie obecność lokalnych plików. Nie sprawdzono połączeń, certyfikatów ani zdalnych usług.")
        elif profile == "test":
            paths = [".novabank/env/test.env"]
            lines.append("TEST: testy Maven/Testcontainers; nie jest osobnym uruchomionym bankiem na portach DEV.")
        lines.extend(f"{path}: {'istnieje' if (self.root / path).is_file() else 'brak'}" for path in paths)
        return "\n".join(lines) + "\n"

    def start_apps(self, job_id: str) -> None:
        java = java_executable()
        if not java:
            raise RuntimeError("Brak Java. Ustaw JAVA_HOME na JDK 25 w systemie, w którym działa panel.")
        for name in ("project", "dev"):
            if not (self.root / f".novabank/env/{name}.env").is_file():
                raise ProblemError("CONFIG_FILES_MISSING")
        dev = env_values(self.root / ".novabank/env/dev.env")
        if not dev.get("TOTP_ENCRYPTION_KEY") or not dev.get("BANK_API_KEY"):
            raise ProblemError("CONFIG_FILES_MISSING")
        settings = env_values(self.root / ".novabank/env/project.env") | dev
        plans = []
        for name, directory, artifact, port_key, default in (
            ("bank", self.root, "Bank-demo", "BANK_APP_PORT", 8080),
            ("shop", self.root / "shop-app", "shop-app", "SHOP_APP_PORT", 8081),
        ):
            if name in self.apps and self.apps[name].poll() is None:
                self.append(job_id, f"{name}: już uruchomiony przez panel.\n")
                continue
            jars = list((directory / "target").glob(f"{artifact}-*.jar"))
            if len(jars) != 1:
                raise ProblemError("BUILD_REQUIRED", component=name)
            jar = jars[0]
            sources = [directory / "pom.xml", *(directory / "src").rglob("*")]
            if any(path.is_file() and path.stat().st_mtime > jar.stat().st_mtime for path in sources):
                raise ProblemError("BUILD_REQUIRED", component=name)
            port = int(settings.get(port_key, default))
            if not 1024 <= port <= 65535:
                raise RuntimeError(f"Nieprawidłowy lokalny port {name}.")
            if port_open(port):
                raise ProblemError("PORT_IN_USE", component=name, port=port)
            plans.append((name, jar, port))
        started = []
        try:
            for name, jar, port in plans:
                if job_id in self.cancelled:
                    raise RuntimeError("Anulowano start aplikacji.")
                log_path = self.runtime / f"{name}-dev.log"
                environment = clean_environment()
                process = subprocess.Popen([java, "-jar", str(jar), "--spring.profiles.active=dev",
                                            "--server.address=127.0.0.1", f"--server.port={port}"],
                                           cwd=self.root, env=environment, stdin=subprocess.DEVNULL,
                                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **process_options())
                with self.lock:
                    self.apps[name] = process
                started.append(process)
                secret_values = self.known_secrets()

                def app_log(app_process=process, path=log_path, values=secret_values):
                    with path.open("w", encoding="utf-8") as log_file:
                        path.chmod(0o600)
                        total = 0

                        def emit(line):
                            nonlocal total
                            if total < 10_000_000:
                                log_file.write(line)
                                log_file.flush()
                                total += len(line)
                        safe_output(app_process.stdout, emit, values)
                        app_process.stdout.close()
                reader = threading.Thread(target=app_log, daemon=True)
                reader.start()
                self.app_readers.append(reader)
                self.append(job_id, f"{name}: uruchomiono proces DEV (PID {process.pid}), port {port}. Gotowość sprawdź na pulpicie.\n")
            time.sleep(0.5)
            if job_id in self.cancelled or any(process.poll() is not None for process in started):
                raise RuntimeError("Start przerwany lub proces zakończył się od razu. Sprawdź lokalny log aplikacji.")
        except Exception:
            for process in started:
                stop_process(process)
            raise

    def stop_apps(self) -> str:
        with self.lock:
            apps = list(self.apps.items())
        for _, process in apps:
            stop_process(process)
        for reader in self.app_readers:
            reader.join(timeout=3)
        self.app_readers = [reader for reader in self.app_readers if reader.is_alive()]
        return "Zatrzymano tylko procesy DEV tej instancji panelu. Bazy i aplikacje z IDE pozostają bez zmian.\n"

    def execute(self, job_id: str, action: Action, profile: str) -> None:
        code = 1
        problem_id = None
        private = self.private_parameters.pop(job_id, {})
        try:
            secret_values = () if action.id.startswith("audit-") or action.id in RECOVERY_ACTIONS or action.id in ("environment-check", "prod-check", "intellij-info", "first-run-guide", "artifacts-cleanup", "artifacts-delete") or action.id in PRODUCTION_PARAMETERS else self.known_secrets()
            secret_values += tuple(value for value in private.values() if isinstance(value, str) and value)
            if action.id in RECOVERY_ACTIONS:
                secret_values += tuple(private.get("keys", {}).values())
            self.append(job_id, f"[{now()}] {action.title} / {profile.upper()}\n")
            if self.job_settings.get(job_id):
                self.append(job_id, "Ustawienia operacji (snapshot): " + json.dumps(self.job_settings[job_id], ensure_ascii=False) + "\n")
            if profile == "prod":
                self.append(job_id, "PROD: Jawnie potwierdzono operację i ostrzeżenie. Zamknięcie strony nie cofa wykonanych zmian.\n")
            if action.id in ("environment-check", "prod-check"):
                self.append(job_id, self.configuration_report(profile))
                if profile in ("dev", "test"):
                    names = ("project.env", "dev.env") if profile == "dev" else ("test.env",)
                    if any(not (self.root / ".novabank/env" / name).is_file() for name in names):
                        self.notify_problem("CONFIG_FILES_MISSING", profile, job_id)
                code = 0
            elif action.id in RECOVERY_ACTIONS:
                try:
                    result = self.totp_recovery.run(action.id, private, self.job_settings[job_id], job_id,
                                                    java_executable(), clean_environment(),
                                                    lambda line: self.append(job_id, line, secret_values),
                                                    lambda: job_id in self.cancelled)
                except Exception:
                    self.append(job_id, 'PODSUMOWANIE: test odzyskania 2FA NIEZALICZONY lub anulowany. Nie potwierdzono pełnego odszyfrowania. Sprawdź wcześniejszy etap i komunikat sprzątania. Nie wykonano rotacji ani logowania.\n')
                    raise
                self.jobs[job_id]["result"] = result
                code = 0
            elif action.id in MAINTENANCE_ACTIONS:
                command = maintenance_command(self.root, java_executable(), action.id, profile, private)
                code = self.run_command(job_id, command, time.monotonic()+action.timeout, secret_values)
            elif action.id in PRODUCTION_PARAMETERS:
                self.production.run(action.id, private, lambda line: self.append(job_id, line, secret_values))
                code = 0
            elif action.id == REPAIR_ACTION:
                self.support.repair(private, lambda line: self.append(job_id, line))
                code = 0
            elif action.id in ("artifacts-cleanup", "artifacts-delete", "artifacts-archive"):
                settings = self.job_settings[job_id]
                self.append(job_id, artifact_cleanup(self.root, job_id, delete=action.id == "artifacts-delete",
                                                     retention_days=settings["artifactRetentionDays"], temp_days=settings["tempRetentionDays"]))
                code = 0
            elif action.id in ("migrate-cli-backup", "migrate-cli-dr"):
                self.legacy_schedules.migrate("backup" if action.id.endswith("backup") else "dr", private["revision"], clean_environment(), lambda line: self.append(job_id, line))
                code = 0
            elif action.id == "import-cli-history":
                self.append(job_id, self.cli_history.import_now())
                code = 0
            elif action.id == "operator-settings":
                self.append(job_id, "Otwórz Ustawienia operacji: katalog kopii, retencje, harmonogramy, migracja timerów i historia CLI. Sam odczyt nie zmienia plików ani timerów.\n")
                code = 0
            elif action.id == "intellij-info":
                self.append(job_id, "NovaBank DEV: Bank DEV + Shop DEV; EnvFile .novabank/env/dev.env.\nNovaBank PROD: Bank PROD (.novabank/env/prod.bank.env) + Shop PROD (.novabank/env/prod.shop.env).\nNovaBank Control Center: WSL bash ./scripts/start.sh.\nWspółdzielone pliki .run nie zawierają sekretów. Po zmianie env zrestartuj odpowiednią konfigurację.\n")
                code = 0
            elif action.id == "first-run-guide":
                self.append(job_id, "Przewodnik: wymagania WSL → konfiguracja → infrastruktura → build → start → testy/monitoring. Każdy krok jest jawny; otwórz kafelek przewodnika w Operacjach.\n")
                code = 0
            elif action.id == "dev-env":
                with self.lock:
                    self.dev_configuration.save(self.dev_configuration.state()["revision"], {}, True)
                self.append(job_id, "Utworzono/uzupełniono pliki DEV; istniejące sekrety zachowano. Pozostałe dane uzupełnisz w zakładce Konfiguracja DEV.\n")
                code = 0
            elif action.id == "test-env":
                with self.lock:
                    config = self.configuration_files.state("test")
                    values = {entry["key"] for file in config["files"] for entry in file["entries"]}
                    initial = {"TEST_BANK_API_KEY": secrets.token_hex(32),
                               "TEST_TOTP_ENCRYPTION_KEY": __import__("base64").b64encode(secrets.token_bytes(32)).decode()}
                    self.configuration_files.save("test", "test", config["revision"],
                                                  {key: value for key, value in initial.items() if key not in values}, [], True, False)
                self.append(job_id, "Przygotowano test.env. Testowe ustawienia edytujesz w Pliki i sekrety.\n")
                code = 0
            elif action.id == "api-key-rotate":
                config = self.configuration_files.state("dev")
                self.configuration_files.save("dev", "dev", config["revision"], {"BANK_API_KEY": secrets.token_hex(32)}, [], True, True)
                self.append(job_id, "Wygenerowano nowy klucz API DEV bez wypisywania. Zrestartuj bank i sklep. Poprzedni klucz nie został zachowany przez panel.\n")
                code = 0
            elif action.id == "apps-start":
                self.start_apps(job_id)
                code = 0
            elif action.id == "apps-stop":
                self.append(job_id, self.stop_apps())
                code = 0
            elif action.id in ("cash-credit", "cash-debit"):
                parameters = self.jobs[job_id]["parameters"]
                result = self.cash().adjust("CREDIT" if action.id == "cash-credit" else "DEBIT", parameters)
                self.jobs[job_id]["result"] = result
                replay = "Wcześniej wykonana korekta; nie naliczono ponownie." if result["replayed"] else "Korekta zatwierdzona w bazie DEV."
                self.append(job_id, f"{replay}\nKonto ID {result['accountId']}: {result['before']} → {result['after']} PLN.\n"
                                   f"Identyfikator korekty: {parameters['requestId']}\n")
                code = 0
            elif action.id == "apps-logs":
                for name in ("bank", "shop"):
                    path = self.runtime / f"{name}-dev.log"
                    self.append(job_id, f"\n--- {name} DEV, proces panelu ---\n")
                    if path.is_file():
                        with path.open("rb") as source:
                            source.seek(max(0, path.stat().st_size - 64_000))
                            safe_output(source, lambda line: self.append(job_id, line), secret_values)
                    else:
                        self.append(job_id, "Brak logu. Aplikacja nie została uruchomiona przez panel.\n")
                code = 0
            else:
                deadline = time.monotonic() + action.timeout
                actions = ("unit-bank", "unit-shop") if action.id == "unit-all" else ("build-bank", "build-shop") if action.id == "build-all" else ("build-prod-bank", "build-prod-shop") if action.id == "build-prod-all" else (action.id,)
                for action_id in actions:
                    if action_id in ("backup-schedule-install", "dr-schedule-install"):
                        self.legacy_schedules.install("backup" if action_id.startswith("backup") else "dr", clean_environment(), lambda line: self.append(job_id, line), self.job_settings[job_id])
                        code = 0
                        continue
                    elif action_id.startswith(("backup-schedule-", "dr-schedule-")):
                        kind = "backup" if action_id.startswith("backup") else "dr"
                        self.legacy_schedules.guard_managed(kind)
                        if action_id.endswith("run-now"):
                            self.legacy_schedules.verify(kind)
                    if action_id == "monitoring-setup":
                        self.support.preflight_monitoring()
                    code = self.run_command(job_id, self.command(action_id, profile), deadline, secret_values)
                    if action_id.endswith("schedule-run-now"):
                        runtime = self.legacy_schedules.runtime(kind)
                        if code != 0 or runtime["execution"] != "SUCCESS":
                            raise ProblemError("SCHEDULE_EXECUTION_FAILED")
                    if code != 0 or job_id in self.cancelled:
                        break
        except Exception as exc:
            problem_id = self.notify_problem(getattr(exc, "issue_code", "TASK_FAILED"), profile, job_id, getattr(exc, "context", None))
            self.append(job_id, f"\nBłąd: {exc}\n", locals().get("secret_values", ()))
        finally:
            if action.id == REPAIR_ACTION and private.get("requestId"):
                try:
                    self.notifications.finish_repair(private["requestId"], "success" if code == 0 else "failed")
                except (OSError, RuntimeError, sqlite3.Error):
                    self.append(job_id, "\nNie udało się zakończyć audytu próby. Nie ponawiaj automatycznie; sprawdź wynik i kopie.\n")
            if action.id in RECOVERY_ACTIONS:
                private.get("keys", {}).clear()
            private.clear()
            self.private_parameters.pop(job_id, None)
            self.job_settings.pop(job_id, None)
            with self.lock:
                job = self.jobs[job_id]
                job.update(status="cancelled" if job_id in self.cancelled else ("success" if code == 0 else "failed"),
                           exitCode=code, finished=now())
                self.cancelled.discard(job_id)
                if code != 0 and problem_id is None:
                    self.notify_problem("TASK_FAILED", profile, job_id)
                self.append(job_id, f"\n[{job['finished']}] Status: {job['status']}, kod: {code}\n")
                try:
                    self.runtime.mkdir(parents=True, exist_ok=True, mode=0o700)
                    path = self.runtime / f"job-{job_id}.json"
                    path.write_text(json.dumps(job, ensure_ascii=False), encoding="utf-8")
                    path.chmod(0o600)
                    for old in sorted(self.runtime.glob("job-*.json"), key=lambda item: item.stat().st_mtime, reverse=True)[60:]:
                        old.unlink()
                except OSError:
                    job["log"] += "\nNie udało się zapisać lokalnej historii zadania.\n"

    def cancel(self, job_id: str) -> None:
        with self.lock:
            job = self.jobs.get(job_id)
            if not job or job["status"] != "running":
                raise ValueError("Zadanie nie jest aktywne.")
            if job["action"] in ("cash-credit", "cash-debit", REPAIR_ACTION, "migrate-cli-backup", "migrate-cli-dr", "backup-schedule-install", "dr-schedule-install", "backup-schedule-run-now", "dr-schedule-run-now") or job["action"] in PRODUCTION_PARAMETERS:
                raise ValueError("Rozpoczętej korekty salda, naprawy plików lub procedury Vault/TLS nie można anulować w połowie. Poczekaj na wynik i sprawdź stan przed ponowieniem.")
            self.cancelled.add(job_id)
            process = self.processes.get(job_id)
        if process:
            stop_process(process)

    def load_history(self) -> None:
        paths = sorted(self.runtime.glob("job-*.json"), key=lambda item: item.stat().st_mtime)[-60:]
        for path in paths:
            if path.stat().st_size > MAX_LOG * 6:
                continue
            try:
                job = json.loads(path.read_text(encoding="utf-8"))
                if job["action"] in ACTION_MAP and re.fullmatch(r"[0-9a-f]{32}", job["id"]):
                    if job["status"] == "running":
                        job["status"] = "interrupted"
                        self.notify("Control Center", "ERROR", f"Przerwane zadanie: {job['title']}. Sprawdź wynik przed ponowieniem.", f"job:{job['id']}:interrupted")
                    self.jobs[job["id"]] = job
            except (OSError, ValueError, KeyError, TypeError):
                continue

    def close(self) -> None:
        self.notification_stop.set()
        if self.notification_thread:
            self.notification_thread.join(timeout=5)
        with self.lock:
            self.closing = True
            ids = [job["id"] for job in self.jobs.values() if job["status"] == "running"]
        for job_id in ids:
            try:
                self.cancel(job_id)
            except (ValueError, OSError, RuntimeError):
                pass
        for thread in self.threads:
            # Keep the instance lock during graceful shutdown of Vault/TLS.
            thread.join(timeout=25 if thread.daemon else None)
        self.stop_apps()


def port_open(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.5):
            return True
    except OSError:
        return False


def documentation_links(panel: Panel) -> dict:
    settings = env_values(panel.root / ".novabank/env/project.env") | env_values(panel.root / ".novabank/env/dev.env")
    result = {}
    for name, key, default in (("bank", "BANK_APP_PORT", 8080), ("shop", "SHOP_APP_PORT", 8081)):
        try:
            port = int(settings.get(key, default))
            if not 1 <= port <= 65535:
                raise ValueError
        except (ValueError, TypeError):
            result[name] = {"swagger": None, "openapi": None, "note": "Nieprawidłowy port aplikacji w konfiguracji DEV."}
            continue
        base = f"http://localhost:{port}"
        result[name] = {"swagger": base + "/swagger-ui/index.html", "openapi": base + "/v3/api-docs",
                        "note": "Lokalny HTTP/DEV. Aplikacja musi być uruchomiona; link nie potwierdza jej dostępności ani profilu."}
    return result


def service_status(panel: Panel, profile: str) -> dict:
    # No remote URLs from request/config: avoid SSRF and misleading PROD status.
    if profile != "dev":
        return {"services": [], "note": "TEST używa Maven/Testcontainers." if profile == "test" else "PROD: ten pulpit nie sonduje usług. Jawne procedury Vault/TLS/build są w Operacjach; zdalna produkcja nie jest sprawdzana.", "checked": now()}
    settings = env_values(panel.root / ".novabank/env/project.env") | env_values(panel.root / ".novabank/env/dev.env")
    services = [("NovaBank", "BANK_APP_PORT", 8080, "Aplikacja"), ("NovaTech Shop", "SHOP_APP_PORT", 8081, "Aplikacja"),
                ("PostgreSQL · bank", "POSTGRES_PORT", 5300, "Infrastruktura"), ("PostgreSQL · sklep", "SHOP_POSTGRES_PORT", 5400, "Infrastruktura"),
                ("Redis", "REDIS_PORT", 6379, "Infrastruktura"), ("Mailpit · SMTP", "MAILPIT_SMTP_PORT", 11026, "Infrastruktura"),
                ("Mailpit · WWW", "MAILPIT_HTTP_PORT", 18026, "Infrastruktura"), ("Prometheus", "", 9090, "Monitoring"), ("Grafana", "", 3000, "Monitoring")]

    def probe(item: tuple) -> dict:
        name, key, default, group = item
        try:
            port = int(settings.get(key, default))
            if not 1 <= port <= 65535:
                raise ValueError
        except (ValueError, TypeError):
            return {"name": name, "port": None, "up": False, "group": group, "detail": "Nieprawidłowy port w konfiguracji."}
        up = port_open(port)
        return {"name": name, "port": port, "up": up, "group": group, "detail": "Port TCP otwarty — nie oznacza gotowości aplikacji." if up else "Port TCP niedostępny z systemu panelu."}

    with concurrent.futures.ThreadPoolExecutor(max_workers=7) as executor:
        result = list(executor.map(probe, services))
    return {"services": result, "checked": now(), "note": "Sondy lokalnych portów TCP, nie test logowania ani healthcheck. Status HTTP/Compose sprawdzaj w diagnostyce."}


class Handler(BaseHTTPRequestHandler):
    server_version = "NovaBankControl/1"

    def setup(self) -> None:
        super().setup()
        self.connection.settimeout(15)

    @property
    def panel(self) -> Panel:
        return self.server.panel

    def log_message(self, fmt: str, *args) -> None:
        # Do not log arbitrary request/query values or tokens.
        pass

    def trusted_request(self) -> bool:
        port = self.server.server_address[1]
        if self.headers.get("Host") not in (f"127.0.0.1:{port}", f"localhost:{port}"):
            return False
        origin = self.headers.get("Origin")
        if origin and origin != f"http://{self.headers['Host']}":
            return False
        return self.headers.get("Sec-Fetch-Site", "none") in ("none", "same-origin")

    def send(self, status: int, data: bytes, content_type: str = "application/json; charset=utf-8") -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'none'; form-action 'self'")
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def json(self, status: int, value: dict) -> None:
        self.send(status, json.dumps(value, ensure_ascii=False).encode("utf-8"))

    def do_GET(self) -> None:
        if not self.trusted_request():
            return self.json(403, {"error": "Niedozwolony Host lub Origin."})
        url = urlsplit(self.path)
        query = parse_qs(url.query)
        if url.path == "/api/ping":
            return self.json(200, {"ok": True, "application": "novabank-control-center"})
        if url.path == "/api/openapi.json":
            return self.json(200, openapi_document([asdict(action) for action in ACTIONS]))
        if url.path == "/api/bootstrap":
            return self.json(200, {"token": self.panel.token, "actions": [asdict(action) for action in ACTIONS],
                                   "project": "NovaBank + NovaTech Shop", "platform": sys.platform, "jobs": self.panel.public_jobs(),
                                   "documentation": documentation_links(self.panel)})
        if url.path in ("/api/operations/settings", "/api/operations/legacy"):
            if not hmac.compare_digest(self.headers.get("X-Control-Center-Token", ""), self.panel.token):
                return self.json(403, {"error": "Brak autoryzacji ustawień operacji."})
            try:
                query = parse_qs(url.query, keep_blank_values=True)
                if query not in ({"profile": ["dev"]}, {"profile": ["test"]}):
                    raise ValueError("Wymagany pojedynczy profil DEV/TEST.")
                profile = query["profile"][0]
                if url.path.endswith("settings"):
                    return self.json(200, self.panel.operation_settings.state(profile))
                with self.panel.lock:
                    if self.panel.closing:
                        return self.json(409, {"error": "Trwa zamykanie panelu."})
                    running = [job["action"] for job in self.panel.jobs.values() if job["status"] == "running"]
                    updating = any(action in ("backup-schedule-install", "dr-schedule-install", "migrate-cli-backup", "migrate-cli-dr", "backup-schedule-remove", "dr-schedule-remove") for action in running)
                    if profile != "dev":
                        timers = {"available": False, "timers": [], "managed": [], "note": "Migracja timerów jest tylko w DEV."}
                    elif running:
                        timers = {"available": False, "timers": [], "managed": [] if updating else [self.panel.legacy_schedules.runtime(kind) for kind in ("backup", "dr")],
                                  "note": "Trwa aktualizacja jednostek; poczekaj na wynik." if updating else "Trwa zadanie. Dostępny jest odczyt wykonania; plany migracji pozostają zablokowane."}
                    else:
                        timers = self.panel.legacy_schedules.state()
                    for schedule in timers.get("managed", []):
                        code = "SCHEDULE_CONFIGURATION_INVALID" if schedule["configuration"] == "INVALID" else "SCHEDULE_EXECUTION_FAILED" if schedule["execution"] == "FAILED" else None
                        if code:
                            guide = problem_metadata(code)
                            # Stable fingerprint: repeated reads do not multiply the same alert.
                            fingerprint = hashlib.sha256(json.dumps({k: schedule[k] for k in ("kind", "configuration", "execution", "lastStarted", "lastFinished", "exitCode")}, sort_keys=True).encode()).hexdigest()
                            self.panel.notify("Control Center", "ERROR", guide["title"], "schedule:" + fingerprint + ":" + code,
                                              issue_code=code, profile="dev")
                    return self.json(200, {"schedules": timers, "history": self.panel.cli_history.state()})
            except (ValueError, UnicodeError):
                return self.json(400, {"error": "Nieprawidłowy profil, plik ustawień lub historia CLI. Sprawdź instrukcję Ustawień operacji."})
            except (OSError, RuntimeError):
                return self.json(503, {"error": "Nie można odczytać ustawień/archiwum CLI. Sprawdź WSL, ścieżki i uprawnienia."})
        support_detail = re.fullmatch(r"/api/support/([0-9]{1,19})", url.path)
        if support_detail:
            if not hmac.compare_digest(self.headers.get("X-Control-Center-Token", ""), self.panel.token):
                return self.json(403, {"error": "Brak autoryzacji diagnostyki."})
            try:
                if parse_qs(url.query, keep_blank_values=True):
                    raise ValueError("Diagnostyka nie przyjmuje dodatkowych parametrów.")
                return self.json(200, self.panel.support.details(int(support_detail[1])))
            except LookupError:
                return self.json(404, {"error": "Nie znaleziono powiadomienia."})
            except (ValueError, UnicodeError):
                return self.json(400, {"error": "Nieprawidłowy format diagnostyki."})
            except (OSError, RuntimeError, sqlite3.Error):
                return self.json(503, {"error": "Diagnostyka niedostępna. Sprawdź lokalne pliki i uprawnienia."})
        if url.path == "/api/config/files":
            if not hmac.compare_digest(self.headers.get("X-Control-Center-Token", ""), self.panel.token):
                return self.json(403, {"error": "Brak autoryzacji odczytu plików."})
            try:
                query = parse_qs(url.query, keep_blank_values=True)
                if query not in ({"profile": ["dev"]}, {"profile": ["test"]}):
                    raise ValueError("Wymagane dokładnie jedno profile=dev lub test.")
                return self.json(200, self.panel.configuration_files.state(query["profile"][0]))
            except (ValueError, UnicodeError):
                return self.json(400, {"error": "Nieprawidłowy profil, format pliku lub dowiązanie konfiguracji."})
            except (OSError, RuntimeError):
                return self.json(503, {"error": "Nie można odczytać plików. Sprawdź katalogi i uprawnienia."})
        if url.path == "/api/config/dev":
            if not hmac.compare_digest(self.headers.get("X-Control-Center-Token", ""), self.panel.token):
                return self.json(403, {"error": "Brak autoryzacji odczytu konfiguracji."})
            try:
                if parse_qs(url.query, keep_blank_values=True) != {"profile": ["dev"]}:
                    raise ValueError("Konfiguracja dostępna tylko dla profile=dev, bez innych parametrów.")
                return self.json(200, self.panel.dev_configuration.state())
            except (ValueError, UnicodeError) as exc:
                return self.json(400, {"error": str(exc) if not isinstance(exc, UnicodeError) else "Nieprawidłowe kodowanie konfiguracji."})
            except (OSError, RuntimeError):
                self.panel.notify("Control Center", "WARN", "Nie można odczytać plików konfiguracji DEV. Sprawdź katalog env i uprawnienia.", f"configuration-read:{now()}")
                return self.json(503, {"error": "Nie można odczytać plików konfiguracji DEV. Sprawdź uprawnienia i katalog env."})
        notification_detail = re.fullmatch(r"/api/notifications/([0-9]{1,19})", url.path)
        case_detail=re.fullmatch(r"/api/payment-cases/([0-9]{1,19})",url.path)
        if url.path=="/api/payment-cases" or case_detail:
            if not hmac.compare_digest(self.headers.get("X-Control-Center-Token",""),self.panel.token):return self.json(403,{"error":"Brak autoryzacji lokalnego odczytu."})
            acquired=False
            try:
                values=payment_case_filters(parse_qs(url.query,keep_blank_values=True),bool(case_detail))
                if case_detail:validate_account_id(case_detail[1])
                acquired=self.panel.account_reads.acquire(blocking=False)
                if not acquired:return self.json(503,{"error":"Trwa odczyt. Spróbuj ponownie."})
                reader=self.panel.payment_cases()
                return self.json(200,reader.detail(case_detail[1]) if case_detail else reader.listing(values))
            except ValueError:return self.json(400,{"error":"Nieprawidłowe parametry sprawy."})
            except LookupError:return self.json(404,{"error":"Nie znaleziono zamówienia."})
            except (OSError,RuntimeError):return self.json(503,{"error":"Wymagany aktualny sklep ze schematem V15 i lokalny Docker DEV. Sprawdź Testy i logi."})
            finally:
                if acquired:self.panel.account_reads.release()
        shop_account_detail = re.fullmatch(r"/api/shop-accounts/([0-9]{1,19})", url.path)
        if url.path == "/api/shop-accounts" or shop_account_detail:
            if not hmac.compare_digest(self.headers.get("X-Control-Center-Token", ""), self.panel.token):
                return self.json(403, {"error": "Brak autoryzacji lokalnego odczytu."})
            acquired = False
            try:
                values = shop_account_filters(parse_qs(url.query, keep_blank_values=True), detail=bool(shop_account_detail))
                identifier = validate_account_id(shop_account_detail[1]) if shop_account_detail else None
                acquired = self.panel.account_reads.acquire(blocking=False)
                if not acquired:
                    return self.json(503, {"error": "Trwa odczyt kont. Poczekaj chwilę i odśwież."})
                reader = self.panel.shop_accounts()
                return self.json(200, reader.detail(identifier) if identifier else reader.listing(values))
            except LookupError as exc:
                return self.json(404, {"error": str(exc)})
            except ValueError:
                return self.json(400, {"error": "Nieprawidłowe parametry odczytu kont sklepu DEV."})
            except (OSError, RuntimeError, EOFError):
                ident = self.panel.notify_problem("SHOP_ACCOUNTS_READ_FAILED", "dev")
                return self.json(503, {"error": "Nie można pobrać kont sklepu DEV. Sprawdź instrukcję w powiadomieniu.", "issueCode": "SHOP_ACCOUNTS_READ_FAILED", "notificationId": ident})
            finally:
                if acquired:
                    self.panel.account_reads.release()
        bank_account_detail = re.fullmatch(r"/api/bank-accounts/([0-9]{1,19})", url.path)
        if url.path == "/api/bank-accounts" or bank_account_detail:
            if not hmac.compare_digest(self.headers.get("X-Control-Center-Token", ""), self.panel.token):
                return self.json(403, {"error": "Brak autoryzacji lokalnego odczytu."})
            acquired = False
            try:
                values = account_filters(parse_qs(url.query, keep_blank_values=True), detail=bool(bank_account_detail))
                identifier = validate_account_id(bank_account_detail[1]) if bank_account_detail else None
                acquired = self.panel.account_reads.acquire(blocking=False)
                if not acquired:
                    return self.json(503, {"error": "Trwa odczyt rachunków. Poczekaj chwilę i odśwież."})
                reader = DevAccounts(self.panel.cash())
                return self.json(200, reader.detail(identifier) if identifier else reader.listing(values))
            except LookupError as exc:
                return self.json(404, {"error": str(exc)})
            except ValueError:
                return self.json(400, {"error": "Nieprawidłowe parametry odczytu rachunków DEV."})
            except (OSError, RuntimeError, EOFError):
                ident = self.panel.notify_problem("ACCOUNTS_READ_FAILED", "dev")
                return self.json(503, {"error": "Nie można pobrać rachunków DEV. Sprawdź instrukcję w powiadomieniu.", "issueCode": "ACCOUNTS_READ_FAILED", "notificationId": ident})
            finally:
                if acquired:
                    self.panel.account_reads.release()
        if url.path in ("/api/accounts", "/api/notifications") or notification_detail:
            if not hmac.compare_digest(self.headers.get("X-Control-Center-Token", ""), self.panel.token):
                return self.json(403, {"error": "Brak autoryzacji lokalnego odczytu."})
            try:
                if url.path == "/api/accounts":
                    if query != {"profile": ["dev"]}:
                        raise ValueError("Lista kont dostępna wyłącznie w DEV.")
                    return self.json(200, self.panel.cash().accounts())
                if notification_detail:
                    if query:
                        raise ValueError("Szczegóły powiadomienia nie przyjmują parametrów.")
                    return self.json(200, {"notification": self.panel.notifications.detail(int(notification_detail[1]))})
                if set(query) - {"before", "view"} or len(query.get("before", [])) > 1 or len(query.get("view", [])) > 1:
                    raise ValueError("Nieprawidłowy kursor powiadomień.")
                before = int(query["before"][0]) if "before" in query else None
                return self.json(200, self.panel.notifications.page(before, query.get("view", ["active"])[0]))
            except LookupError as exc:
                return self.json(404, {"error": str(exc)})
            except ValueError as exc:
                return self.json(400, {"error": str(exc)})
            except (OSError, RuntimeError, EOFError, sqlite3.Error):
                if url.path == "/api/accounts":
                    self.panel.notify("Control Center", "WARN", "Nie można pobrać kont DEV. Sprawdź Docker, konfigurację i migracje banku.", f"accounts-read:{now()}")
                return self.json(503, {"error": "Nie można odczytać danych. Sprawdź Docker/migracje DEV lub lokalne logi i uprawnienia."})
        if url.path == "/api/status":
            profile = query.get("profile", ["dev"])[0]
            if profile not in ("dev", "test", "prod"):
                return self.json(400, {"error": "Nieznany profil."})
            return self.json(200, service_status(self.panel, profile))
        if url.path == "/api/jobs":
            return self.json(200, {"jobs": self.panel.public_jobs()})
        if re.fullmatch(r"/api/jobs/[0-9a-f]{32}", url.path):
            with self.panel.lock:
                job = self.panel.jobs.get(url.path.rsplit("/", 1)[1])
                return self.json(200 if job else 404, job or {"error": "Nie znaleziono zadania."})
        if url.path in ("/docs/guide", "/docs/panel"):
            path = self.panel.root / DOCUMENTS[url.path.rsplit("/", 1)[1]]
            if path.is_file():
                return self.send(200, path.read_bytes(), "text/plain; charset=utf-8")
        assets = {"/": ("index.html", "text/html"), "/index.html": ("index.html", "text/html"),
                  "/payment-cases.js": ("payment-cases.js", "text/javascript"),
                  "/payment-cases.css": ("payment-cases.css", "text/css"),
                  "/app.js": ("app.js", "text/javascript"), "/styles.css": ("styles.css", "text/css"),
                  "/configuration-editor.js": ("configuration-editor.js", "text/javascript"),
                  "/production-ui.js": ("production-ui.js", "text/javascript"),
                  "/guided-ui.js": ("guided-ui.js", "text/javascript"),
                  "/panel-language.js": ("panel-language.js", "text/javascript"),
                  "/docs/api": ("api-docs.html", "text/html"), "/api-docs.js": ("api-docs.js", "text/javascript")}
        assets["/problem-ui.js"] = ("problem-ui.js", "text/javascript")
        assets["/bank-accounts.js"] = ("bank-accounts.js", "text/javascript")
        assets["/bank-accounts.css"] = ("bank-accounts.css", "text/css")
        assets["/shop-accounts.js"] = ("shop-accounts.js", "text/javascript")
        assets["/operator-settings.js"] = ("operator-settings.js", "text/javascript")
        assets["/notification-retention.js"] = ("notification-retention.js", "text/javascript")
        assets["/maintenance-ui.js"] = ("maintenance-ui.js", "text/javascript")
        assets["/totp-recovery-ui.js"] = ("totp-recovery-ui.js", "text/javascript")
        assets["/operator-settings.css"] = ("operator-settings.css", "text/css")
        if url.path in assets:
            name, mime = assets[url.path]
            return self.send(200, (WEB_ROOT / name).read_bytes(), mime + "; charset=utf-8")
        return self.json(404, {"error": "Nie znaleziono zasobu."})

    def do_POST(self) -> None:
        if not self.trusted_request() or not hmac.compare_digest(self.headers.get("X-Control-Center-Token", ""), self.panel.token):
            return self.json(403, {"error": "Brak autoryzacji lokalnej operacji."})
        if self.headers.get("Content-Type", "").split(";")[0] != "application/json":
            return self.json(415, {"error": "Wymagany application/json."})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            limit = 32768 if self.path == "/api/config/files/save" else 8192 if self.path == "/api/jobs" else 4096
            if not 0 < length <= limit:
                return self.json(413, {"error": "Nieprawidłowy rozmiar żądania."})
            payload = json.loads(self.rfile.read(length))
            if not isinstance(payload, dict):
                raise ValueError("Wymagany obiekt JSON.")
            if self.path == "/api/operations/settings":
                if set(payload) != {"profile", "revision", "values", "confirmed"}:
                    raise ValueError("Nieprawidłowe pola ustawień operacyjnych.")
                with self.panel.lock:
                    if self.panel.closing or any(job["status"] == "running" for job in self.panel.jobs.values()):
                        raise BusyError("Poczekaj na zakończenie zadania przed zmianą ustawień.")
                    return self.json(200, self.panel.operation_settings.save(payload["profile"], payload["revision"], payload["values"], payload["confirmed"]))
            if self.path == "/api/support/check":
                if set(payload) != {"id"}:
                    raise ValueError("Diagnostyka przyjmuje wyłącznie identyfikator powiadomienia.")
                with self.panel.lock:
                    if self.panel.closing or any(job["status"] == "running" for job in self.panel.jobs.values()):
                        raise BusyError("Poczekaj na zakończenie zadania przed weryfikacją.")
                    return self.json(200, self.panel.support.check(payload["id"]))
            if self.path in ("/api/config/files/save", "/api/config/files/reveal"):
                fields = {"profile", "file", "revision", "confirmed"}
                expected = fields | ({"key"} if self.path.endswith("reveal") else {"values", "remove", "acknowledgeRisk"})
                if set(payload) != expected:
                    raise ValueError("Nieprawidłowe pola żądania edytora.")
                with self.panel.lock:
                    if self.panel.closing or any(job["status"] == "running" for job in self.panel.jobs.values()):
                        raise BusyError("Edytor wymaga zakończenia bieżącego zadania.")
                    if self.path.endswith("reveal"):
                        result = self.panel.configuration_files.reveal(payload["profile"], payload["file"], payload["key"], payload["revision"], payload["confirmed"])
                    else:
                        result = self.panel.configuration_files.save(payload["profile"], payload["file"], payload["revision"], payload["values"], payload["remove"], payload["confirmed"], payload["acknowledgeRisk"])
                        self.panel.notify("Control Center", "WARN", "Zmieniono lokalny plik konfiguracji. Wymagany restart zależnych procesów; haseł w bazie nie zmieniono.", f"config-edit:{uuid.uuid4().hex}")
                    return self.json(200, result)
            if self.path == "/api/config/dev":
                if set(payload) != {"profile", "revision", "values", "confirmed"} or payload["profile"] != "dev":
                    raise ValueError("Dozwolona jest wyłącznie konfiguracja DEV i pola formularza.")
                with self.panel.lock:
                    if self.panel.closing or any(job["status"] == "running" for job in self.panel.jobs.values()):
                        raise BusyError("Zapis konfiguracji wymaga zakończenia bieżącego zadania.")
                    return self.json(200, self.panel.dev_configuration.save(payload["revision"], payload["values"], payload["confirmed"]))
            if self.path == "/api/notifications/retention":
                with self.panel.lock:
                    if self.panel.closing:
                        raise BusyError("Trwa zamykanie panelu.")
                    return self.json(200, self.panel.notification_retention.action(payload))
            if self.path == "/api/notifications/action":
                if set(payload) not in ({"operation", "confirmed"}, {"operation", "id", "confirmed"}):
                    raise ValueError("Nieprawidłowe pola operacji powiadomień.")
                if not isinstance(payload["operation"], str) or type(payload["confirmed"]) is not bool:
                    raise ValueError("Nieprawidłowe typy operacji powiadomień.")
                if payload["operation"] != "delete-active" and "id" not in payload:
                    raise ValueError("Wymagany identyfikator powiadomienia.")
                if payload["operation"] == "delete-active" and "id" in payload:
                    raise ValueError("Operacja zbiorcza nie przyjmuje identyfikatora.")
                return self.json(200, self.panel.notifications.change(payload["operation"], payload.get("id"), payload["confirmed"]))
            if self.path == "/api/jobs" and {"action", "profile", "confirmed"} <= set(payload) <= {"action", "profile", "confirmed", "parameters", "productionConfirmation"}:
                if not isinstance(payload["action"], str) or not isinstance(payload["profile"], str) or type(payload["confirmed"]) is not bool:
                    raise ValueError("Nieprawidłowe typy pól.")
                return self.json(202, self.panel.submit(payload["action"], payload["profile"], payload["confirmed"], payload.get("parameters"), payload.get("productionConfirmation")))
            if self.path == "/api/cancel" and set(payload) == {"id"}:
                if not isinstance(payload["id"], str) or not re.fullmatch(r"[0-9a-f]{32}", payload["id"]):
                    raise ValueError("Nieprawidłowy identyfikator.")
                self.panel.cancel(payload["id"])
                return self.json(200, {"ok": True})
            return self.json(404, {"error": "Brak takiej operacji."})
        except (BusyError, ConfigurationConflict) as exc:
            code = "TASK_BUSY" if isinstance(exc, BusyError) else getattr(exc, "issue_code", "CONFIG_CONFLICT_MANUAL")
            event_id = self.panel.notify_problem(code, payload.get("profile") if isinstance(payload, dict) else None)
            return self.json(409, {"error": str(exc), "issueCode": code, "notificationId": event_id})
        except LookupError as exc:
            return self.json(404, {"error": str(exc)})
        except (ValueError, TypeError, UnicodeError) as exc:
            if self.path.startswith("/api/config/files") and isinstance(exc, UnicodeError):
                return self.json(400, {"error": "Nieprawidłowe kodowanie pliku konfiguracji."})
            return self.json(400, {"error": str(exc) if not isinstance(exc, json.JSONDecodeError) else "Niepoprawny JSON."})
        except (OSError, RuntimeError, sqlite3.Error):
            if self.path == "/api/operations/settings":
                ident = self.panel.notify_problem("OPERATOR_SETTINGS_FAILED", payload.get("profile"))
                return self.json(503, {"error": "Nie potwierdzono zapisu ustawień operacji. Sprawdź powiadomienie i uprawnienia; timerów nie zmieniono.", "issueCode": "OPERATOR_SETTINGS_FAILED", "notificationId": ident})
            if self.path.startswith("/api/config/files"):
                ident = self.panel.notify_problem("CONFIG_WRITE_FAILED", payload.get("profile"))
                return self.json(503, {"error": "Operacja plikowa nie powiodła się. Sprawdź pliki i kopie przed startem aplikacji.", "issueCode": "CONFIG_WRITE_FAILED", "notificationId": ident})
            if self.path == "/api/config/dev":
                ident = self.panel.notify_problem("CONFIG_WRITE_FAILED", "dev")
                return self.json(503, {"error": "Nie udało się zapisać konfiguracji DEV. Sprawdź uprawnienia i pliki env; nie uruchamiaj aplikacji bez sprawdzenia obu plików.", "issueCode": "CONFIG_WRITE_FAILED", "notificationId": ident})
            if self.path == "/api/jobs":
                self.panel.notify("Control Center", "ERROR", "Nie udało się uruchomić zadania. Operacja nie została rozpoczęta; sprawdź proces panelu i zasoby systemu.", f"job-start:{now()}")
                return self.json(500, {"error": "Nie udało się uruchomić zadania. Operacja nie została rozpoczęta."})
            return self.json(503, {"error": "Nie udało się zmienić powiadomień."}) if self.path == "/api/notifications/action" else self.json(500, {"error": "Nie udało się zatrzymać procesu."})


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, port: int, panel: Panel):
        super().__init__(("127.0.0.1", port), Handler)
        self.panel = panel


class InstanceLock:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.file = path.open("a+b")
        if path.stat().st_size == 0:
            self.file.write(b"1")
            self.file.flush()
        self.file.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.file.close()
            raise RuntimeError("Inna instancja panelu już używa tego repozytorium.") from None

    def close(self) -> None:
        if os.name == "nt":
            import msvcrt
            self.file.seek(0)
            msvcrt.locking(self.file.fileno(), msvcrt.LK_UNLCK, 1)
        self.file.close()


def main() -> int:
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18110)
    parser.add_argument("--no-browser", action="store_true")
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("Port musi być w zakresie 1024–65535.")
    panel = Panel()
    instance = None
    server = None
    try:
        instance = InstanceLock(panel.runtime / "server.lock")
        panel.load_history()
        server = Server(args.port, panel)
        panel.start_notifications()
        url = f"http://127.0.0.1:{args.port}"
        print(f"NovaBank Control Center: {url}", flush=True)
        print("DEV/TEST + diagnostyka plików PROD. Ctrl+C zamyka panel i jego procesy DEV.", flush=True)
        if not args.no_browser:
            is_wsl = os.name != "nt" and Path("/proc/version").exists() and "microsoft" in Path("/proc/version").read_text().lower()
            if is_wsl:
                opener = shutil.which("wslview")
                if opener:
                    subprocess.Popen([opener, url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                elif Path("/mnt/c/Windows/explorer.exe").is_file():
                    subprocess.Popen(["/mnt/c/Windows/explorer.exe", url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            else:
                webbrowser.open(url)
        if hasattr(signal, "SIGTERM"):
            signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown, daemon=True).start())
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    except (OSError, RuntimeError) as exc:
        print(f"Nie uruchomiono panelu: {exc}", file=sys.stderr)
        return 1
    finally:
        panel.close()
        if server:
            server.server_close()
        if instance:
            instance.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
