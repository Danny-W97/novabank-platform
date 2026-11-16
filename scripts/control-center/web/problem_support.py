"""Local runbooks, read-only rechecks and one explicit, backed-up DEV repair."""
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import socket
import secrets
import uuid

from configuration_files import ConfigurationFiles
from dev_configuration import ConfigurationConflict, assignments, upsert, validate
from env_ownership import normalize_pair
from problems import ProblemError, metadata

REPAIR_ACTION = "repair-metrics-dev"


class MonitoringFiles(ConfigurationFiles):
    EXTRA = ".novabank/monitoring/prometheus.yml"

    def target(self, file):
        if file != "prometheus":
            if file not in ("project", "dev", "monitoring"):
                raise ValueError("Plik poza zakresem naprawy.")
            return super().target(file)
        # Reuse the ancestor/symlink checks on the adjacent fixed monitoring file.
        parent = super().target("monitoring").parent
        path = parent / "prometheus.yml"
        if path.is_symlink() or getattr(path, "is_junction", lambda: False)() or (path.exists() and (not path.is_file() or path.stat().st_nlink != 1)):
            raise ValueError("Niedozwolony plik Prometheusa.")
        return path

    def snapshot(self):
        result = {}
        for name in ("project", "dev", "monitoring", "prometheus"):
            path = self.target(name)
            if path.exists() and path.stat().st_size > 131072:
                raise ValueError("Konfiguracja przekracza limit naprawy.")
            raw = path.read_bytes() if path.exists() else None
            if raw is not None:
                raw.decode("utf-8-sig")
            result[name] = raw
        return result


def artifact_current(root, component):
    if component not in ("bank", "shop"):
        return False
    directory = root if component == "bank" else root / "shop-app"
    prefix = "Bank-demo" if component == "bank" else "shop-app"
    jars = list((directory / "target").glob(prefix + "-*.jar"))
    if len(jars) != 1 or not jars[0].is_file() or jars[0].is_symlink():
        return False
    stamp = jars[0].stat().st_mtime
    return not any(path.is_file() and path.stat().st_mtime > stamp
                   for path in (directory / "pom.xml", *(directory / "src").rglob("*")))


def render_prometheus(raw, username, password, ports):
    """Preserve other jobs/settings; refuse custom/remote targets and ambiguous YAML."""
    text = raw.decode("utf-8-sig")
    newline = "\r\n" if "\r\n" in text else "\n"
    output, active, auth = [], None, False
    counts = {name: {key: 0 for key in ("job", "user", "password", "target", "auth", "metrics_path", "scheme", "static_configs", "labels", "service", "environment")} for name in ports}
    for line in text.splitlines():
        job = re.fullmatch(r"\s*- job_name:\s*['\"]?([A-Za-z0-9_-]+)['\"]?\s*", line)
        if job:
            active = job[1] if job[1] in ports else None
            auth = False
            if active:
                counts[active]["job"] += 1
        if active:
            # Only the checked-in local job shape is repairable. In particular,
            # service discovery, proxies, YAML merges and reserved target labels
            # could send the copied credential somewhere other than localhost.
            if line.strip() and not line.lstrip().startswith("#"):
                allowed = (
                    r"  - job_name:\s*['\"]?(?:bank-demo|shop-app)['\"]?\s*",
                    r"    metrics_path:\s*['\"]?/actuator/prometheus['\"]?\s*",
                    r"    scheme:\s*http\s*",
                    r"    (?:basic_auth|static_configs):\s*",
                    r"      (?:username|password):\s*.*",
                    r"      - targets:\s*\[['\"](?:localhost|127\.0\.0\.1):[0-9]{4,5}['\"]\]\s*",
                    r"        labels:\s*",
                    r"          (?:service|environment):\s*['\"]?[A-Za-z0-9_-]+['\"]?\s*",
                )
                if not any(re.fullmatch(pattern, line) for pattern in allowed):
                    raise ValueError("Niestandardowe zadanie Prometheusa wymaga ręcznej kontroli; nic nie zapisano.")
                field = re.fullmatch(r"\s*(metrics_path|scheme|static_configs|labels|service|environment):.*", line)
                if field:
                    counts[active][field[1]] += 1
            if re.match(r"\s*(?:proxy_url|relabel_configs|metric_relabel_configs|password_file|username_file|authorization):", line) or re.match(r"\s*[A-Za-z_]+:\s*[&*!]", line):
                raise ValueError("Niestandardowy Prometheus wymaga ręcznej kontroli; nic nie zapisano.")
            if re.fullmatch(r"\s*basic_auth:\s*", line):
                auth = True
                counts[active]["auth"] += 1
            elif re.match(r"\s*(username|password):", line):
                match = re.fullmatch(r"(\s*)(username|password):\s*.*", line)
                if not auth or not match:
                    raise ValueError("Niejednoznaczny Basic Auth; nic nie zapisano.")
                key = match[2]
                counts[active]["user" if key == "username" else "password"] += 1
                value = username if key == "username" else password
                line = match[1] + key + ": '" + value.replace("'", "''") + "'"
            elif "targets:" in line:
                target = re.fullmatch(r"\s*- targets:\s*\[['\"](?:localhost|127\.0\.0\.1):([0-9]{4,5})['\"]\]\s*", line)
                if not target or int(target[1]) != ports[active]:
                    raise ValueError("Inny target/port Prometheusa — użyj instrukcji i konfiguracji monitoringu.")
                counts[active]["target"] += 1
            if re.match(r"\s*scheme:", line) and not re.fullmatch(r"\s*scheme:\s*http\s*", line):
                raise ValueError("Inny schemat Prometheusa wymaga ręcznej kontroli.")
        output.append(line)
    if any(any(value != 1 for value in fields.values()) for fields in counts.values()):
        raise ValueError("Nie potwierdzono dwóch standardowych zadań Basic Auth; nic nie zapisano.")
    bom = b"\xef\xbb\xbf" if raw.startswith(b"\xef\xbb\xbf") else b""
    return bom + (newline.join(output) + newline).encode("utf-8")


def validate_repair_parameters(parameters):
    required = {"notificationId", "revision", "planToken", "requestId"}
    if not isinstance(parameters, dict) or set(parameters) != required:
        raise ValueError("Naprawa wymaga planu i dokładnie wskazanych parametrów.")
    result = dict(parameters)
    if type(result["notificationId"]) is not int or not 1 <= result["notificationId"] <= 9223372036854775807:
        raise ValueError("Nieprawidłowy identyfikator powiadomienia.")
    if any(not isinstance(result[key], str) or not re.fullmatch(r"[0-9a-f]{64}", result[key]) for key in ("revision", "planToken")):
        raise ValueError("Nieprawidłowa rewizja lub token planu.")
    if not isinstance(result["requestId"], str) or str(uuid.UUID(result["requestId"])) != result["requestId"]:
        raise ValueError("Wymagany kanoniczny UUID ponowienia.")
    return result


class ProblemSupport:
    def __init__(self, panel):
        self.panel = panel
        self.plan_key = secrets.token_bytes(32)  # Unlike the browser's CSRF token, never exposed.
        self.files = MonitoringFiles(panel.root, panel.token, panel.dev_configuration.lock)

    def token(self, notification_id, revision):
        return hmac.new(self.plan_key, f"metrics-dev:{notification_id}:{revision}".encode(), hashlib.sha256).hexdigest()

    @staticmethod
    def credentials(snapshot):
        canonical, _ = normalize_pair(snapshot)
        if any(canonical[name] != snapshot[name] for name in canonical):
            raise ConfigurationConflict("Najpierw uzgodnij właścicieli zmiennych DEV.")
        dev = assignments(snapshot["dev"] or b"")
        monitor = assignments(snapshot["monitoring"] or b"")
        return dev, monitor

    def preflight_monitoring(self):
        with self.files.lock:
            snapshot = self.files.snapshot()
            dev, monitor = self.credentials(snapshot)
            if snapshot["dev"] is not None and snapshot["monitoring"] is not None:
                for key in ("METRICS_USERNAME", "METRICS_PASSWORD"):
                    if dev.get(key) and monitor.get(key) and dev[key] != monitor[key]:
                        raise ProblemError("METRICS_CREDENTIALS_CONFLICT")

    def replacement(self, snapshot):
        dev, monitor = self.credentials(snapshot)
        if snapshot["dev"] is None or snapshot["monitoring"] is None:
            raise ValueError("Najpierw utwórz konfigurację DEV i monitoringu.")
        username = validate("METRICS_USERNAME", dev.get("METRICS_USERNAME", ""))
        password = validate("METRICS_PASSWORD", dev.get("METRICS_PASSWORD", ""))
        ports = {}
        for name, key, default in (("bank-demo", "BANK_MANAGEMENT_PORT", "8082"), ("shop-app", "SHOP_MANAGEMENT_PORT", "8083")):
            value = dev.get(key, default)
            validate(key, value)
            ports[name] = int(value)
        raw = snapshot["prometheus"]
        if raw is None:
            raise ValueError("Brak Prometheusa — najpierw przygotuj monitoring zgodnie z instrukcją.")
        updated = render_prometheus(raw, username, password, ports)
        return {"monitoring": upsert(snapshot["monitoring"], {"METRICS_USERNAME": username, "METRICS_PASSWORD": password}), "prometheus": updated}

    def details(self, notification_id):
        item = self.panel.notifications.detail(notification_id)
        guide = metadata(item.get("issue_code"))
        result = {"notificationId": item["id"], "problem": guide, "profile": item.get("profile"),
                  "jobId": item.get("job_id"), "context": item["issue_context"], "outcome": item["outcome"],
                  "outcomeNote": item.get("outcome_note"), "actions": [], "plan": None,
                  "diagnosis": "Instrukcja nie wykonuje operacji. Archiwizacja nie oznacza naprawy."}
        result["readOnly"] = item.get("profile") not in ("dev", "test") or item["state"] == "deleted"
        if item.get("profile") not in ("dev", "test") or item["state"] == "deleted":
            result["diagnosis"] = "Dostępna wyłącznie instrukcja. Ta wersja napraw działa tylko dla DEV/TEST i poza koszem."
            return result
        code = guide["code"]
        if code == "CONFIG_FILES_MISSING":
            result["actions"] = [{"kind": "view", "id": "config", "title": "Otwórz kreator DEV"}] if item["profile"] == "dev" else [{"kind": "job", "id": "test-env", "title": "Przygotuj konfigurację TEST"}]
        elif code == "BUILD_REQUIRED" and item["issue_context"].get("component") in ("bank", "shop"):
            component = item["issue_context"]["component"]
            result["actions"] = [{"kind": "job", "id": "build-" + component, "title": "Buduj " + ("bank" if component == "bank" else "sklep")}]
        elif code == "METRICS_CREDENTIALS_CONFLICT" and item["profile"] == "dev":
            result["actions"] = [{"kind": "view", "id": "files", "title": "Otwórz Pliki i sekrety"}]
            try:
                with self.files.lock:
                    snapshot = self.files.snapshot()
                    new = self.replacement(snapshot)
                    changed = any(snapshot[name] != raw for name, raw in new.items())
                    result["diagnosis"] = "Potwierdzono możliwość uzgodnienia standardowej konfiguracji z istniejącym sekretem DEV." if changed else "Pliki są już zgodne. Nie potwierdzono przeładowania usług."
                    if changed:
                        revision = self.files.revision(snapshot)
                        result["plan"] = {"revision": revision, "planToken": self.token(item["id"], revision),
                                          "title": "Uzgodnij monitoring z istniejącymi danymi DEV",
                                          "source": ".novabank/env/dev.env",
                                          "writes": [".novabank/monitoring/.env.monitoring", ".novabank/monitoring/prometheus.yml"],
                                          "effects": "Kopia oryginałów, zapis dwóch plików i kontrola zgodności. Bez nowego hasła, zapisu dev.env, rotacji Grafany, DB lub restartu usług.",
                                          "restartRequired": True}
            except (ValueError, ConfigurationConflict, OSError, UnicodeError):
                result["diagnosis"] = "Automatyczny plan niedostępny: niepełna/niestandardowa konfiguracja, inny target, konflikt lub brak dostępu. Nic nie zapisano; skorzystaj z instrukcji."
        return result

    def check(self, notification_id):
        item = self.panel.notifications.detail(notification_id)
        if item["state"] == "deleted" or item.get("profile") not in ("dev", "test"):
            raise ValueError("Diagnostyka niedostępna dla tego powiadomienia/profilu.")
        code = item.get("issue_code")
        outcome, note = "manual", "Ten przypadek wymaga świadomej kontroli; instrukcja nie potwierdza rozwiązania."
        try:
            if code == "METRICS_CREDENTIALS_CONFLICT":
                with self.files.lock:
                    snapshot = self.files.snapshot()
                    new = self.replacement(snapshot)
                    ok = all(snapshot[name] == value for name, value in new.items())
                outcome = "configuration_verified" if ok else "open"
                note = "Pliki monitoringu zgodne z DEV; wymagane świadome przeładowanie i sprawdzenie usług." if ok else "Konfiguracja monitoringu nadal różni się od DEV."
            elif code == "CONFIG_FILES_MISSING":
                if item["profile"] == "dev":
                    ok = self.panel.dev_configuration.state()["ready"]
                else:
                    values = self.panel.configuration_files.test_environment()
                    ok = bool(values.get("TEST_BANK_API_KEY") and values.get("TEST_TOTP_ENCRYPTION_KEY"))
                outcome = "configuration_verified" if ok else "open"
                note = "Potwierdzono podstawową konfigurację, nie działanie baz ani aplikacji." if ok else "Podstawowa konfiguracja wymaga uzupełnienia."
            elif code == "BUILD_REQUIRED":
                ok = artifact_current(self.panel.root, item["issue_context"].get("component"))
                outcome = "condition_cleared" if ok else "open"
                note = "JAR istnieje i nie jest starszy niż źródła; nie uruchamiano aplikacji." if ok else "Nadal brakuje jednego aktualnego JAR-a."
            elif code == "PORT_IN_USE":
                port = item["issue_context"].get("port")
                if type(port) is int and 1024 <= port <= 65535:
                    with socket.socket() as connection:
                        connection.settimeout(0.3)
                        occupied = connection.connect_ex(("127.0.0.1", port)) == 0
                    outcome = "open" if occupied else "condition_cleared"
                    note = "Port nadal zajęty; nie identyfikowano ani nie zatrzymywano procesu." if occupied else "Port nie odpowiada na TCP; nie oznacza to działającej aplikacji."
        except (ValueError, ConfigurationConflict, OSError, UnicodeError):
            outcome, note = "open", "Nie udało się potwierdzić poprawności. Sprawdź instrukcję; nie zmieniono plików."
        self.panel.notifications.mark_outcome(notification_id, outcome, note)
        return self.details(notification_id)

    def validate_plan(self, parameters):
        params = validate_repair_parameters(parameters)
        item = self.panel.notifications.detail(params["notificationId"])
        if item.get("issue_code") != "METRICS_CREDENTIALS_CONFLICT" or item.get("profile") != "dev" or item["state"] == "deleted":
            raise ValueError("Powiadomienie nie uprawnia do tej naprawy DEV.")
        if not hmac.compare_digest(params["planToken"], self.token(params["notificationId"], params["revision"])):
            raise ValueError("Plan nie pochodzi z tej instancji Control Center.")
        return params

    def repair(self, parameters, emit):
        params = self.validate_plan(parameters)
        with self.files.lock:
            original = self.files.snapshot()
            self.files.check_revision(params["revision"], original)
            new = self.replacement(original)
            base = self.panel.runtime / "repair-backups"
            for node in (self.panel.runtime, self.panel.runtime.parent, self.panel.runtime.parent.parent, base):
                if node.is_symlink() or getattr(node, "is_junction", lambda: False)():
                    raise ValueError("Dowiązanie katalogu kopii jest niedozwolone.")
            base.mkdir(mode=0o700, exist_ok=True)
            backup = base / params["requestId"]
            backup.mkdir(mode=0o700)  # Never overwrite an earlier backup, even after interruption.
            for name in new:  # Back up only files being changed, not unrelated API/TOTP secrets.
                raw = original[name]
                if raw is not None:
                    with os.fdopen(os.open(backup / (name + ".backup"), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as stream:
                        stream.write(raw)
                        stream.flush()
                        os.fsync(stream.fileno())
            emit("Kopia oryginałów: " + backup.relative_to(self.panel.root).as_posix() + ". Zawiera sekrety; nie udostępniaj.\n")
            self.files.commit(original, new)
            expected = dict(original) | new
            if self.files.snapshot() != expected:
                raise ConfigurationConflict("Stan zmienił się po zapisie. Zachowano kopię; sprawdź pliki przed dalszym działaniem.")
            emit("Skopiowano istniejące dane metryk DEV do dwóch plików monitoringu. Nie zmieniono dev.env, Grafany, DB ani procesów.\n")
            emit("Konfiguracja zweryfikowana. Usługi nie zostały przeładowane; po świadomym restarcie sprawdź targets UP.\n")
        self.panel.notifications.mark_outcome(params["notificationId"], "configuration_verified", "Zgodność plików potwierdzona; przeładowanie i zdrowie usług pozostają do sprawdzenia.")
