"""Isolated tests: no real balances, Docker or application processes are touched."""
import json
import gzip
from pathlib import Path
import tempfile
import time
import sqlite3
import unittest
from unittest.mock import patch

from dev_cash import DevCash, adjustment_sql, normalized_path, validate_cash
from notifications import Notifications
from server import ACTION_MAP, Panel, redact

REQUEST = {"accountId": 1, "amount": "100.00", "requestId": "35c3d10c-7a2c-4e15-a5b8-4b47631c0dd5"}


class CashTests(unittest.TestCase):
    def test_decimal_validation_and_normalization(self):
        self.assertEqual("1.20", validate_cash(REQUEST | {"amount": "1.2"})["amount"])
        self.assertEqual("999999999.99", validate_cash(REQUEST | {"amount": "999999999.99"})["amount"])

    def test_rejects_invalid_precision_injection_types_and_ids(self):
        cases = [{"amount": value} for value in ("0", "-1", "1.001", "1e2", "NaN", "1;DROP TABLE accounts", "01", "1000000000", "1,2", 1.2)]
        cases += [{"accountId": value} for value in (0, -1, True, "1", 2**63)]
        cases += [{"requestId": value} for value in (None, "uuid", "' OR true", "35C3D10C-7A2C-4E15-A5B8-4B47631C0DD5")]
        for changed in cases:
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                validate_cash(REQUEST | changed)
        with self.assertRaises(ValueError):
            validate_cash(REQUEST | {"command": "not-allowed"})

    def test_sql_uses_lock_transaction_audit_and_version(self):
        sql = adjustment_sql("DEBIT", REQUEST)
        for expected in ("BEGIN;", "COMMIT;", "FOR UPDATE", "pg_advisory_xact_lock", "version = a.version + 1", "INSERT INTO dev_cash_adjustments", "-100.00", "IDEMPOTENCY_CONFLICT", "balance + (-100.00)::numeric >= 0"):
            self.assertIn(expected, sql)
        with self.assertRaises(ValueError):
            adjustment_sql("DELETE", REQUEST)

    def test_windows_and_wsl_compose_paths_match(self):
        self.assertEqual(normalized_path("C:\\Bank Workspace\\stuff\\Bank-demo"), normalized_path("/mnt/c/Bank Workspace/stuff/Bank-demo/"))

    def test_local_container_verification(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            cash = DevCash(root, {}, {})
            labels = {"com.docker.compose.project": "bank-demo", "com.docker.compose.service": "postgres",
                      "com.docker.compose.project.config_files": str(root / "docker-compose.yaml"),
                      "com.docker.compose.project.working_dir": str(root)}
            with patch("dev_cash.os.name", "posix"), patch("dev_cash.shutil.which", return_value="docker"), patch.object(cash, "_run", return_value=json.dumps({"running": True, "labels": labels})):
                command = cash._base_command()
                self.assertEqual(["docker", "--host", "unix:///var/run/docker.sock"], command[:3])
                self.assertIn("bank-postgres", command)
                for field, value in (("com.docker.compose.project", "production"), ("com.docker.compose.service", "other"), ("com.docker.compose.project.config_files", "/other/docker-compose.yaml"), ("com.docker.compose.project.working_dir", "/other")):
                    with patch.object(cash, "_run", return_value=json.dumps({"running": True, "labels": labels | {field: value}})), self.assertRaises(RuntimeError):
                        cash._base_command()
                cash.settings = {"POSTGRES_HOST": "remote.example"}
                with self.assertRaises(RuntimeError):
                    cash._base_command()

    def test_cash_only_dev_and_requires_confirmation_and_parameters(self):
        with tempfile.TemporaryDirectory() as temp:
            panel = Panel(Path(temp))
            try:
                for profile in ("test", "prod"):
                    with self.assertRaises(ValueError):
                        panel.submit("cash-credit", profile, True, REQUEST)
                with self.assertRaises(ValueError):
                    panel.submit("cash-credit", "dev", False, REQUEST)
                with self.assertRaises(ValueError):
                    panel.submit("cash-credit", "dev", True)
                with self.assertRaises(ValueError):
                    panel.submit("environment-check", "dev", False, REQUEST)
            finally:
                panel.close()

    def test_cash_result_is_logged_without_executing_database(self):
        with tempfile.TemporaryDirectory() as temp:
            panel = Panel(Path(temp))
            with patch.object(DevCash, "adjust", return_value={"accountId": 1, "before": "50.00", "after": "150.00", "replayed": False}):
                job = panel.submit("cash-credit", "dev", True, REQUEST)
                panel.threads[-1].join(3)
            self.assertEqual("success", panel.jobs[job["id"]]["status"])
            self.assertIn("150.00", panel.jobs[job["id"]]["log"])
            panel.close()

    def test_started_cash_cannot_be_cancelled(self):
        with tempfile.TemporaryDirectory() as temp:
            panel = Panel(Path(temp))
            panel.jobs["cash"] = {"action": "cash-credit", "status": "running"}
            with self.assertRaisesRegex(ValueError, "nie można anulować"):
                panel.cancel("cash")
            panel.jobs["cash"]["status"] = "failed"
            panel.close()


class NotificationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.runtime = self.root / ".novabank/control-center/web"
        self.store = Notifications(self.root, self.runtime, redact)
        self.log = self.root / ".novabank/logs/bank-dev.log"
        self.log.parent.mkdir(parents=True)

    def tearDown(self):
        self.temp.cleanup()

    def test_persistence_pagination_and_redaction(self):
        for index in range(61):
            self.store.add("Bank", "WARN", f"Problem {index}: api_key=hidden-value user@example.test", fingerprint=str(index))
        first = self.store.page()
        self.assertEqual(61, first["total"])
        self.assertEqual(50, len(first["items"]))
        restarted = Notifications(self.root, self.runtime, redact)
        second = restarted.page(first["next"])
        self.assertEqual(11, len(second["items"]))
        self.assertIsNone(second["next"])
        self.assertNotIn("hidden-value", json.dumps(first))
        self.assertNotIn("user@example.test", json.dumps(first))
        self.assertNotIn("hidden-value", self.store.database.read_bytes().decode("utf-8", errors="ignore"))

    def test_archive_delete_restore_and_reimport_do_not_resurrect_dismissed_alerts(self):
        self.store.add("Bank", "WARN", "Fixture", fingerprint="same-event")
        notification_id = self.store.page()["items"][0]["id"]
        self.store.change("archive", notification_id)
        self.assertEqual(0, self.store.page()["total"])
        self.assertEqual(1, self.store.page(view="archived")["total"])
        self.store.add("Bank", "WARN", "Fixture", fingerprint="same-event")
        restarted = Notifications(self.root, self.runtime, redact)
        self.assertEqual(0, restarted.page()["total"])
        self.assertEqual("archived", restarted.detail(notification_id)["state"])
        self.assertEqual(1, restarted.change("delete", notification_id, True)["changed"])
        self.assertEqual(0, restarted.change("delete", notification_id, True)["changed"])
        self.assertEqual(1, restarted.page(view="deleted")["total"])
        self.assertEqual("archived", restarted.change("restore", notification_id)["notification"]["state"])
        self.assertEqual("active", restarted.change("restore", notification_id)["notification"]["state"])
        self.assertEqual(1, restarted.page()["total"])

    def test_bulk_delete_only_active_requires_confirmation_and_can_be_restored(self):
        for index in range(3):
            self.store.add("Sklep", "ERROR", "Fixture", fingerprint=str(index))
        archived_id = self.store.page()["items"][0]["id"]
        self.store.change("archive", archived_id)
        with self.assertRaises(ValueError):
            self.store.change("delete-active")
        result = self.store.change("delete-active", confirmed=True)
        self.assertEqual(2, result["changed"])
        self.assertEqual({"active": 0, "archived": 1, "deleted": 2}, result["counts"])
        self.assertEqual(0, self.store.change("delete-active", confirmed=True)["changed"])
        deleted_id = self.store.page(view="deleted")["items"][0]["id"]
        self.store.change("restore", deleted_id)
        self.assertEqual(1, self.store.page()["total"])
        self.assertEqual("archived", self.store.detail(archived_id)["state"])

    def test_old_database_migrates_without_losing_records(self):
        legacy_root = self.root / "legacy"
        runtime = legacy_root / "runtime"
        runtime.mkdir(parents=True)
        database = sqlite3.connect(runtime / "notifications.sqlite")
        database.executescript("CREATE TABLE notifications(id INTEGER PRIMARY KEY, fingerprint TEXT UNIQUE NOT NULL, source TEXT NOT NULL, level TEXT NOT NULL, message TEXT NOT NULL, created TEXT NOT NULL);"
                               "INSERT INTO notifications VALUES(7,'fixture','Bank','WARN','Legacy fixture','2026-10-11T14:00:00Z');")
        database.close()
        migrated = Notifications(legacy_root, runtime, redact)
        self.assertEqual("active", migrated.detail(7)["state"])
        self.assertEqual("Legacy fixture", migrated.detail(7)["message"])
        self.assertEqual(1, migrated.page()["total"])

    def test_invalid_actions_ids_states_and_confirmation_cannot_change_notifications(self):
        self.store.add("Bank", "ERROR", "Fixture", fingerprint="event")
        notification_id = self.store.page()["items"][0]["id"]
        for bad_id in (None, True, "1", -1, 0, 2**63):
            with self.assertRaises(ValueError):
                self.store.change("delete", bad_id, True)
        for args in (("purge", notification_id, True), ("delete", notification_id, "true"), ("delete", notification_id, False), ("delete-active", notification_id, True)):
            with self.assertRaises(ValueError):
                self.store.change(*args)
        with self.assertRaises(ValueError):
            self.store.page(view="all; DROP TABLE notifications")
        with self.assertRaises(LookupError):
            self.store.detail(10000)
        self.assertEqual(1, self.store.page()["total"])

    def test_view_pagination_and_revision_track_state_changes(self):
        for index in range(61):
            self.store.add("Bank", "WARN", f"Fixture {index}", fingerprint=str(index))
        first = self.store.page()
        for item in first["items"]:
            self.store.change("archive", item["id"])
        second = self.store.page(view="archived")
        self.assertGreater(second["revision"], first["revision"])
        self.assertEqual(50, second["total"])
        self.assertEqual(11, self.store.page()["total"])
        self.assertTrue(all(item["state"] == "archived" for item in second["items"]))

    def test_warn_error_only_deduplicated_and_partial_line_deferred(self):
        line = "2026-10-11T15:22:40.161+02:00 WARN 123 --- [bank] Logger : Problem\n"
        self.log.write_text("INFO not an issue\n" + line + "ERROR incomplete", encoding="utf-8")
        self.assertEqual(1, self.store.page()["total"])
        (self.runtime / "bank-dev.log").write_text(line, encoding="utf-8")
        self.assertEqual(1, self.store.page()["total"])
        with self.log.open("a", encoding="utf-8") as stream:
            stream.write(" now complete\n")
        self.assertEqual(2, self.store.page()["total"])
        self.assertEqual(2, self.store.page()["total"])

    def test_truncation_detected(self):
        self.log.write_text("WARN first longer warning\n", encoding="utf-8")
        self.assertEqual(1, self.store.page()["total"])
        self.log.write_text("ERROR new\n", encoding="utf-8")
        self.assertEqual(2, self.store.page()["total"])

    def test_known_rolling_archives_are_imported_and_console_duplicate_is_deduplicated(self):
        with gzip.open(self.log.parent / "bank-dev.2026-10-11.0.log.gz", "wt", encoding="utf-8") as stream:
            stream.write("2026-10-11T15:22:40.161+02:00 WARN 123 --- [bank] Full.Logger : Fixture warning\n")
        self.log.write_text("2026-10-11T15:22:40.161+02:00 WARN 123 --- [bank] Short.Logger : Fixture warning\n", encoding="utf-8")
        self.assertEqual(1, self.store.page()["total"])
        self.assertEqual(1, self.store.page()["total"])

    def test_overlong_record_and_private_key_never_parsed(self):
        self.log.write_text("x" * 600_000 + " ERROR secret-tail\n-----BEGIN RSA PRIVATE KEY-----\nERROR private-material\n-----END RSA PRIVATE KEY-----\nWARN allowed\n", encoding="utf-8")
        self.assertEqual(0, self.store.page()["total"])
        page = self.store.page()
        self.assertEqual(1, page["total"])
        self.assertNotIn("secret-tail", json.dumps(page))
        self.assertNotIn("private-material", json.dumps(page))

    def test_missing_logs_have_explicit_status(self):
        self.assertFalse(self.store.page()["sourcesAvailable"])
        self.assertIn("IDE", self.store.page()["note"])

    def test_collector_runs_without_browser_and_stops_with_panel(self):
        self.log.write_text("WARN collected without a browser\n", encoding="utf-8")
        panel = Panel(self.root)
        panel.start_notifications()
        try:
            total = 0
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline and not total:
                # No call to page()/HTTP: only the background worker can ingest the file.
                with panel.notifications.connect() as database:
                    total = database.execute("SELECT count(*) FROM notifications").fetchone()[0]
                if not total:
                    time.sleep(.02)
            self.assertEqual(1, total)
        finally:
            panel.close()
        self.assertFalse(panel.notification_thread.is_alive())

    def test_failed_panel_job_becomes_notification(self):
        panel = Panel(self.root)
        panel.jobs["fixture"] = {"action": "apps-start", "title": "Start", "log": "", "status": "running"}
        with patch.object(panel, "start_apps", side_effect=RuntimeError("Fixture error")):
            panel.execute("fixture", ACTION_MAP["apps-start"], "dev")
        page = panel.notifications.page()
        self.assertTrue(any(item["level"] == "ERROR" for item in page["items"]))
        self.assertTrue(any("Fixture error" in item["message"] for item in page["items"]))
        panel.close()


if __name__ == "__main__":
    unittest.main()
