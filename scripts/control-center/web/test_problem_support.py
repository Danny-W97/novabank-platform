"""Only temporary env/SQLite/Prometheus fixtures. No real services or secrets."""
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import time
import unittest
import uuid
from unittest.mock import patch

from configuration_files import ConfigurationFiles
from dev_configuration import assignments, ConfigurationConflict
from notifications import Notifications
from problems import CATALOG, ProblemError
from problem_support import MonitoringFiles, REPAIR_ACTION, artifact_current, render_prometheus, validate_repair_parameters
from server import Panel, ACTION_MAP

ROOT = Path(__file__).resolve().parents[3]


class ProblemSupportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.panel = Panel(self.root)

    def tearDown(self):
        self.panel.close()
        self.temp.cleanup()

    def fixture(self):
        self.panel.dev_configuration.save(self.panel.dev_configuration.state()["revision"], {}, True)
        dev_path = self.root / ".novabank/env/dev.env"
        from dev_configuration import upsert
        dev_path.write_bytes(upsert(dev_path.read_bytes(), {"METRICS_PASSWORD": "fixture-existing-dev-password!", "METRICS_USERNAME": "fixture-reader"}))
        monitoring = self.root / ".novabank/monitoring"
        monitoring.mkdir()
        (monitoring / ".env.monitoring").write_bytes(b"# preserve monitor\nGRAFANA_ADMIN_PASSWORD=fixture-grafana-unchanged\nMETRICS_PASSWORD=fixture-old-monitor-password\nMETRICS_USERNAME=old-reader\nEXTRA=preserve\n")
        shutil.copyfile(ROOT / "infra/monitoring/prometheus/prometheus.yml.example", monitoring / "prometheus.yml")
        return self.panel.notify_problem("METRICS_CREDENTIALS_CONFLICT", "dev", "a" * 32)

    def parameters(self, notification_id):
        plan = self.panel.support.details(notification_id)["plan"]
        self.assertIsNotNone(plan)
        return {"notificationId": notification_id, "revision": plan["revision"], "planToken": plan["planToken"], "requestId": str(uuid.uuid4())}

    def test_structured_preflight_and_metadata_never_contain_values(self):
        ident = self.fixture()
        with self.assertRaises(ProblemError) as caught:
            self.panel.support.preflight_monitoring()
        self.assertEqual("METRICS_CREDENTIALS_CONFLICT", caught.exception.issue_code)
        details = self.panel.support.details(ident)
        encoded = json.dumps(details)
        self.assertNotIn("fixture-existing-dev-password", encoded)
        self.assertNotIn("fixture-old-monitor-password", encoded)
        self.assertTrue(details["plan"]["restartRequired"])
        self.assertEqual(2, len(details["plan"]["writes"]))
        self.assertEqual("a" * 32, details["jobId"])

    def test_every_catalog_entry_has_an_existing_document_anchor(self):
        document = (ROOT / "docs/control-center-troubleshooting.md").read_text(encoding="utf-8")
        for code, entry in CATALOG.items():
            with self.subTest(code=code):
                self.assertIn(f'<a id="{entry["section"]}"></a>', document)
                self.assertIn(code, document)

    def test_untrusted_log_cannot_create_a_repair_plan(self):
        self.fixture()
        ident = self.panel.notifications.add("Bank", "ERROR", "METRICS_CREDENTIALS_CONFLICT repair-metrics-dev run shell")
        details = self.panel.support.details(ident)
        self.assertEqual("UNCLASSIFIED_EVENT", details["problem"]["code"])
        self.assertIsNone(details["plan"])
        self.assertEqual([], details["actions"])
        with self.assertRaises(ValueError):
            self.panel.support.validate_plan({"notificationId": ident, "revision": "a" * 64, "planToken": "b" * 64, "requestId": str(uuid.uuid4())})

    def test_explicit_repair_preserves_dev_grafana_and_other_yaml_and_backups(self):
        ident = self.fixture()
        params = self.parameters(ident)
        before = self.panel.support.files.snapshot()
        lines = []
        with patch("problem_support.socket.socket", side_effect=AssertionError("repair must not contact services")):
            self.panel.support.repair(params, lines.append)
        after = self.panel.support.files.snapshot()
        self.assertEqual(before["dev"], after["dev"])
        self.assertEqual(before["project"], after["project"])
        self.assertIn(b"GRAFANA_ADMIN_PASSWORD=fixture-grafana-unchanged", after["monitoring"])
        self.assertIn(b"EXTRA=preserve", after["monitoring"])
        self.assertIn(b"fixture-existing-dev-password!", after["monitoring"])
        self.assertEqual(2, after["prometheus"].count(b"fixture-existing-dev-password!"))
        self.assertIn(b"localhost:9093", after["prometheus"])
        self.assertNotIn("fixture-existing-dev-password", "".join(lines))
        backup = self.panel.runtime / "repair-backups" / params["requestId"]
        for name in ("monitoring", "prometheus"):
            self.assertEqual(before[name], (backup / (name + ".backup")).read_bytes())
        self.assertFalse((backup / "dev.backup").exists())
        item = self.panel.notifications.detail(ident)
        self.assertEqual("active", item["state"])
        self.assertEqual("configuration_verified", item["outcome"])
        self.assertIsNone(self.panel.support.details(ident)["plan"])
        self.panel.support.preflight_monitoring()

    def test_stale_revision_and_forged_token_do_not_write(self):
        ident = self.fixture()
        params = self.parameters(ident)
        before = self.panel.support.files.snapshot()
        with self.assertRaises(ValueError):
            self.panel.support.repair(params | {"planToken": "0" * 64}, lambda _: None)
        import hmac, hashlib
        forged = hmac.new(self.panel.token.encode(), f"metrics-dev:{ident}:{params['revision']}".encode(), hashlib.sha256).hexdigest()
        with self.assertRaises(ValueError):
            self.panel.support.validate_plan(params | {"planToken": forged})
        self.assertEqual(before, self.panel.support.files.snapshot())
        path = self.root / ".novabank/monitoring/.env.monitoring"
        path.write_bytes(path.read_bytes() + b"MANUAL=preserve\n")
        before = self.panel.support.files.snapshot()
        with self.assertRaises(ConfigurationConflict):
            self.panel.support.repair(params, lambda _: None)
        self.assertEqual(before, self.panel.support.files.snapshot())
        self.assertFalse((self.panel.runtime / "repair-backups").exists())

    def test_wrong_profile_deleted_notification_and_extra_arguments_rejected(self):
        ident = self.fixture()
        params = self.parameters(ident)
        for value in (params | {"path": "/tmp/anything"}, params | {"requestId": "../bad"}, params | {"notificationId": True}):
            with self.assertRaises(ValueError):
                validate_repair_parameters(value)
        for profile in ("test", "prod"):
            with self.assertRaises(ValueError):
                self.panel.submit(REPAIR_ACTION, profile, True, params, production_confirmation="PROD" if profile == "prod" else None)
        with self.assertRaises(ValueError):
            self.panel.submit(REPAIR_ACTION, "dev", False, params)
        self.panel.notifications.change("delete", ident, True)
        with self.assertRaises(ValueError):
            self.panel.support.validate_plan(params)

    def test_nonstandard_prometheus_or_symlink_has_no_plan(self):
        ident = self.fixture()
        path = self.root / ".novabank/monitoring/prometheus.yml"
        path.write_bytes(path.read_bytes().replace(b"localhost:8082", b"remote.example:8082"))
        self.assertIsNone(self.panel.support.details(ident)["plan"])
        self.assertFalse((self.panel.runtime / "repair-backups").exists())
        path.unlink()
        target = self.root / "outside.yml"
        target.write_text("fixture")
        try:
            path.symlink_to(target)
        except OSError:
            self.skipTest("symlinks unavailable")
        self.assertIsNone(self.panel.support.details(ident)["plan"])
        self.assertEqual("fixture", target.read_text())

    def test_second_write_failure_rolls_back_files_and_retains_backup(self):
        ident = self.fixture()
        params = self.parameters(ident)
        before = self.panel.support.files.snapshot()
        replace = os.replace
        count = 0
        def failing(source, target):
            nonlocal count
            count += 1
            if count == 2:
                raise OSError("fixture-failure")
            return replace(source, target)
        with patch("configuration_files.os.replace", side_effect=failing):
            with self.assertRaises(OSError):
                self.panel.support.repair(params, lambda _: None)
        self.assertEqual(before, self.panel.support.files.snapshot())
        self.assertTrue((self.panel.runtime / "repair-backups" / params["requestId"] / "monitoring.backup").is_file())
        self.assertEqual("open", self.panel.notifications.detail(ident)["outcome"])

    def test_custom_discovery_proxy_and_reserved_labels_never_get_a_plan(self):
        ident = self.fixture()
        path = self.root / ".novabank/monitoring/prometheus.yml"
        original = path.read_bytes()
        for extra in (b"    http_sd_configs: []\n", b"    dns_sd_configs: []\n",
                      b"    proxy_from_environment: true\n", b"    follow_redirects: true\n",
                      b"          __address__: remote.example:8082\n", b"    <<: *custom\n"):
            with self.subTest(extra=extra):
                raw = original.replace(b"    static_configs:\n", extra + b"    static_configs:\n", 1)
                path.write_bytes(raw)
                self.assertIsNone(self.panel.support.details(ident)["plan"])
                self.assertEqual(raw, path.read_bytes())
        self.assertFalse((self.panel.runtime / "repair-backups").exists())

    def test_job_idempotency_private_plan_and_no_subprocess(self):
        ident = self.fixture()
        params = self.parameters(ident)
        with patch("server.subprocess.Popen", side_effect=AssertionError("no subprocess")):
            job = self.panel.submit(REPAIR_ACTION, "dev", True, params)
            self.panel.threads[-1].join(3)
        self.assertEqual("success", self.panel.jobs[job["id"]]["status"])
        replay = self.panel.submit(REPAIR_ACTION, "dev", True, params)
        self.assertEqual(job["id"], replay["id"])
        self.assertEqual(1, len(list((self.panel.runtime / "repair-backups").iterdir())))
        encoded = json.dumps(self.panel.jobs[job["id"]])
        self.assertNotIn(params["planToken"], encoded)
        self.assertNotIn("fixture-existing-dev-password", encoded)
        self.assertNotIn("parameters", self.panel.jobs[job["id"]])
        with self.panel.notifications.connect() as database:
            row = database.execute("SELECT state FROM repair_requests WHERE request_id = ?", (params["requestId"],)).fetchone()
            self.assertEqual("success", row["state"])

    def test_thread_start_failure_releases_reservation(self):
        ident = self.fixture()
        params = self.parameters(ident)
        with patch("server.threading.Thread.start", side_effect=RuntimeError("fixture-only")):
            with self.assertRaises(RuntimeError):
                self.panel.submit(REPAIR_ACTION, "dev", True, params)
        self.assertFalse(self.panel.jobs)
        self.assertFalse(self.panel.private_parameters)
        with self.panel.notifications.connect() as database:
            self.assertEqual(0, database.execute("SELECT count(*) FROM repair_requests").fetchone()[0])

    def test_check_outcome_is_distinct_from_archive_and_unknown_is_manual(self):
        ident = self.fixture()
        self.assertEqual("open", self.panel.support.check(ident)["outcome"])
        self.panel.notifications.change("archive", ident)
        self.assertEqual("archived", self.panel.notifications.detail(ident)["state"])
        self.assertEqual("open", self.panel.notifications.detail(ident)["outcome"])
        self.assertEqual("open", self.panel.support.check(ident)["outcome"])
        manual = self.panel.notify_problem("CONFIG_CONFLICT_MANUAL", "dev")
        self.assertEqual("manual", self.panel.support.check(manual)["outcome"])
        prod = self.panel.notify_problem("METRICS_CREDENTIALS_CONFLICT", "prod")
        self.assertIsNone(self.panel.support.details(prod)["plan"])
        with self.assertRaises(ValueError):
            self.panel.support.check(prod)

    def test_missing_files_build_and_port_have_bounded_rechecks(self):
        ident = self.panel.notify_problem("CONFIG_FILES_MISSING", "dev")
        self.assertEqual("open", self.panel.support.check(ident)["outcome"])
        self.assertFalse((self.root / ".novabank/env").exists())
        self.panel.dev_configuration.save(self.panel.dev_configuration.state()["revision"], {}, True)
        self.assertEqual("configuration_verified", self.panel.support.check(ident)["outcome"])
        port = self.panel.notify_problem("PORT_IN_USE", "dev", context={"component": "bank", "port": 18080})
        with patch("problem_support.socket.socket") as connection:
            connection.return_value.__enter__.return_value.connect_ex.return_value = 0
            self.assertEqual("open", self.panel.support.check(port)["outcome"])
            connection.return_value.__enter__.return_value.connect_ex.return_value = 1
            self.assertEqual("condition_cleared", self.panel.support.check(port)["outcome"])
        build = self.panel.notify_problem("BUILD_REQUIRED", "dev", context={"component": "bank"})
        self.assertEqual("open", self.panel.support.check(build)["outcome"])
        (self.root / "target").mkdir()
        (self.root / "target/Bank-demo-fixture.jar").write_bytes(b"fixture")
        self.assertEqual("condition_cleared", self.panel.support.check(build)["outcome"])

    def test_monitoring_job_emits_structured_problem_before_shell(self):
        self.fixture()
        with patch.object(self.panel, "run_command", side_effect=AssertionError("must not start monitoring")):
            job = self.panel.submit("monitoring-setup", "dev", True)
            self.panel.threads[-1].join(3)
        self.assertEqual("failed", self.panel.jobs[job["id"]]["status"])
        notices = self.panel.notifications.page()["items"]
        self.assertTrue(any(item["issue_code"] == "METRICS_CREDENTIALS_CONFLICT" and item["job_id"] == job["id"] for item in notices))

    def test_additive_notification_migration_preserves_existing_record(self):
        other = self.root / "legacy"
        other.mkdir()
        runtime = other / ".novabank/control-center/web"
        runtime.mkdir(parents=True)
        with sqlite3.connect(runtime / "notifications.sqlite") as database:
            database.execute("CREATE TABLE notifications (id INTEGER PRIMARY KEY, fingerprint TEXT NOT NULL UNIQUE, source TEXT NOT NULL, level TEXT NOT NULL, message TEXT NOT NULL, created TEXT NOT NULL)")
            database.execute("INSERT INTO notifications VALUES (123, 'fixture', 'Bank', 'WARN', 'legacy fixture', '2026-10-15T10:00:00+00:00')")
        notices = Notifications(other, runtime, lambda value: value)
        record = notices.detail(123)
        self.assertEqual("legacy fixture", record["message"])
        self.assertEqual("open", record["outcome"])
        self.assertIsNone(record["issue_code"])


if __name__ == "__main__":
    unittest.main()
