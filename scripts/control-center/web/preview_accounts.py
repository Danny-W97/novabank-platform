"""UI QA with synthetic accounts only. Never invokes Docker, SQL or application jobs."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import tempfile

import server
from dev_shop_accounts import DevShopAccounts


class FixtureDatabase:
    def _base_command(self):
        return ["fixture-only"]

    def _run(self, command, *, sql):
        assert command == ["fixture-only"] and sql.startswith("BEGIN READ ONLY;")
        items = [{"id": str(i), "ownerId": str(i), "owner": "Klient demonstracyjny " + str(i), "email": f"demo{i}@example.invalid",
                  "number": "•••• " + str(i).zfill(4), "balance": f"{i * 100}.00", "currency": "PLN",
                  "status": ["ACTIVE", "BLOCKED", "CLOSED"][i % 3], "userStatus": "ACTIVE"} for i in range(63, 0, -1)]
        identifier = re.search(r"WHERE a.id = ([0-9]+)", sql)
        if identifier:
            item = next((x for x in items if x["id"] == identifier[1]), None)
            if item:
                item.pop("number")
                item.update(accountNumber=item["id"].zfill(26), role="USER", emailVerified=True, twoFactorEnabled=False,
                            createdAt="2026-10-15T10:00:00Z", updatedAt="2026-10-15T10:00:00Z", userCreatedAt="2026-10-15T10:00:00Z", lastLoginAt=None)
            return json.dumps(item)
        encoded = re.search(r"decode\('([0-9a-f]*)', 'hex'\)", sql)[1]
        term = bytes.fromhex(encoded).decode().lower()
        status = re.search(r"a.status = '([A-Z]+)'", sql)
        currency = re.search(r"a.currency = '([A-Z]+)'", sql)
        before = re.search(r"a.id < ([0-9]+)", sql)
        items = [x for x in items if (not status or x["status"] == status[1]) and (not currency or x["currency"] == currency[1])
                 and (not before or int(x["id"]) < int(before[1])) and (not term or term in (x["owner"] + " " + x["email"] + " " + x["id"]).lower())]
        return json.dumps(items[:51])


class FixtureShopDatabase:
    def _base_command(self):
        return ["shop-fixture-only"]

    def _run(self, command, *, sql):
        assert command == ["shop-fixture-only"] and sql.startswith("BEGIN READ ONLY;")
        items = [{"id": str(i), "customerId": f"client-demo-{i}", "email": f"client{i}@example.invalid",
                  "firstName": "Klient demo" if i % 5 else None, "lastName": str(i) if i % 5 else None,
                  "active": bool(i % 3), "role": "ADMIN" if i % 7 == 0 else "CUSTOMER",
                  "createdAt": "2026-10-15T10:00:00Z"} for i in range(63, 0, -1)]
        identifier = re.search(r"WHERE u.id = ([0-9]+)", sql)
        if identifier:
            item = next((x for x in items if x["id"] == identifier[1]), None)
            if item:
                item.update(updatedAt="2026-10-15T11:00:00Z", hasPhone=True, hasAddress=False)
            return json.dumps(item)
        term = bytes.fromhex(re.search(r"decode\('([0-9a-f]*)', 'hex'\)", sql)[1]).decode().lower()
        role = re.search(r"u.role = '([A-Z]+)'", sql)
        active = re.search(r"u.active = (true|false)", sql)
        before = re.search(r"u.id < ([0-9]+)", sql)
        items = [x for x in items if (not role or x["role"] == role[1])
                 and (not active or x["active"] == (active[1] == "true"))
                 and (not before or int(x["id"]) < int(before[1]))
                 and (not term or term in (x["email"] + " " + x["customerId"] + " " + (x["firstName"] or "") + " " + (x["lastName"] or "")).lower() or term == x["id"])]
        return json.dumps(items[:51])


class FixtureSchedules:
    def __init__(self, settings):
        self.settings = settings

    def state(self):
        values = self.settings.state("dev")["values"]
        timers = [{"kind": kind, "revision": character * 64, "legacyTimer": old + ".timer", "newTimer": new + ".timer",
                   "legacyExists": exists, "legacyEnabled": exists, "legacyActive": exists, "serviceRunning": False,
                   "newExists": False, "legacyBackupDirectory": "/mnt/e/Bank-demo-backups/postgres" if exists else None,
                   "canMigrate": exists, "reason": "Gotowy plan demonstracyjny; nie zmienia rzeczywistych timerów." if exists else "Brak starego timera DR w danych demonstracyjnych.", "settings": values}
                  for kind, old, new, exists, character in (("backup", "novabank-backup", "novabank-web-dev-backup", True, "c"),
                                                           ("dr", "novabank-dr-test", "novabank-web-dev-dr-test", False, "d"))]
        managed = [{"kind": kind, "timer": new + ".timer", "service": new + ".service", "installed": True, "enabled": True,
                    "waiting": True, "configuration": "VALID", "configurationNote": "Konfiguracja systemd zweryfikowana — dane demonstracyjne.",
                    "execution": execution, "lastStarted": "Fri 2026-10-16 12:30:00 CEST" if execution == "SUCCESS" else None,
                    "lastFinished": "Fri 2026-10-16 12:30:08 CEST" if execution == "SUCCESS" else None,
                    "exitCode": 0 if execution == "SUCCESS" else None, "nextRun": next_run}
                   for kind, new, execution, next_run in (("backup", "novabank-web-dev-backup", "SUCCESS", "Sat 2026-10-17 02:00:00 CEST"),
                                                         ("dr", "novabank-web-dev-dr-test", "NEVER", "Sun 2026-10-18 04:00:00 CEST"))]
        return {"available": True, "timers": timers, "managed": managed, "note": "Dane demonstracyjne — żadnego połączenia z systemd ani migracji rzeczywistych timerów."}


class PreviewPanel(server.Panel):
    def __init__(self, root):
        super().__init__(root)
        self.legacy_schedules = FixtureSchedules(self.operation_settings)
        self.cli_history.source.write_text("2026-10-16T08:00:00Z | OK | Build banku\n2026-10-16T08:01:00Z | BLAD:1 | Kontrola połączenia Redis\n2026-10-16T08:02:00Z | OK | Konfiguracja DEV\n", encoding="utf-8")
        self.cli_history.import_now()

    def cash(self):
        return FixtureDatabase()

    def shop_accounts(self):
        return DevShopAccounts(FixtureShopDatabase())

    def submit(self, *args, **kwargs):
        raise ValueError("Podgląd QA: operacje środowiska i sald wyłączone.")

    def run_command(self, *args, **kwargs):
        raise AssertionError("No shell commands in UI QA")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=61626)
    args = parser.parse_args()
    server.service_status = lambda *args: {"services": [], "checked": datetime.now(timezone.utc).isoformat(), "note": "Podgląd QA — sondy rzeczywistych usług wyłączone."}
    with tempfile.TemporaryDirectory(prefix="novabank-accounts-preview-") as directory:
        panel = PreviewPanel(Path(directory))
        with server.Server(args.port, panel) as http:
            print(f"Owned synthetic account preview: http://127.0.0.1:{args.port}/", flush=True)
            print("Owned fixture root: " + str(panel.root), flush=True)
            try:
                http.serve_forever()
            except KeyboardInterrupt:
                pass
            finally:
                panel.close()
