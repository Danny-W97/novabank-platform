"""Run with python -B -m unittest discover -s scripts/control-center/web -v.

Only temporary fixtures and mock commands; never starts the bank or Docker.
"""
import http.client
import io
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from server import ACTIONS, ACTION_MAP, BusyError, InstanceLock, Panel, Server, clean_environment, redact, safe_output, service_status


class PanelTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.panel = Panel(self.root)

    def tearDown(self):
        self.panel.close()
        self.temp.cleanup()

    def test_prod_requires_distinct_confirmation_and_keeps_dev_actions_blocked(self):
        for action in ACTIONS:
            with self.assertRaises(ValueError):
                self.panel.submit(action.id, "prod", True)
            if "prod" not in action.profiles:
                with self.assertRaises(ValueError):
                    self.panel.submit(action.id, "prod", True, production_confirmation="PROD")
        self.assertFalse(ACTION_MAP["prod-check"].changes)

    def test_unknown_action_and_shell_payload_rejected(self):
        for action in ("bash", "infra-up; rm -rf /", "prepare_prod", "invalidate_bank_sessions"):
            with self.assertRaises(ValueError):
                self.panel.submit(action, "dev", True)

    def test_changes_require_explicit_confirmation(self):
        with self.assertRaises(ValueError):
            self.panel.submit("apps-start", "dev", False)

    def test_command_builder_also_rejects_prod_and_unknown_actions(self):
        for action, profile in (("infra-up", "prod"), ("shell", "dev")):
            with self.assertRaises(ValueError):
                self.panel.command(action, profile)

    def test_prod_report_does_not_read_secret_values(self):
        self.panel.jobs["report"] = {"log": "", "status": "running"}
        with patch.object(self.panel, "known_secrets", side_effect=AssertionError("must not read secrets")):
            self.panel.execute("report", ACTION_MAP["prod-check"], "prod")
        self.assertEqual("success", self.panel.jobs["report"]["status"])

    def test_start_requires_config_before_spawning(self):
        with patch("server.java_executable", return_value="fake-java"), patch("server.subprocess.Popen") as spawn:
            with self.assertRaises(RuntimeError) as caught:
                self.panel.start_apps("unused")
            self.assertEqual("CONFIG_FILES_MISSING", caught.exception.issue_code)
            spawn.assert_not_called()

    def test_dev_env_action_creates_files_without_leaking_or_rotating_keys(self):
        first = self.panel.submit("dev-env", "dev", True)
        self.panel.threads[-1].join(3)
        self.assertEqual("success", self.panel.jobs[first["id"]]["status"])
        from dev_configuration import assignments
        path = self.root / ".novabank/env/dev.env"
        before = assignments(path.read_bytes())
        self.assertNotIn(before["BANK_API_KEY"], self.panel.jobs[first["id"]]["log"])
        self.assertNotIn(before["TOTP_ENCRYPTION_KEY"], self.panel.jobs[first["id"]]["log"])
        second = self.panel.submit("dev-env", "dev", True)
        self.panel.threads[-1].join(3)
        self.assertEqual("success", self.panel.jobs[second["id"]]["status"])
        after = assignments(path.read_bytes())
        self.assertEqual(before["BANK_API_KEY"], after["BANK_API_KEY"])
        self.assertEqual(before["TOTP_ENCRYPTION_KEY"], after["TOTP_ENCRYPTION_KEY"])

    def prepare_app_fixtures(self):
        env = self.root / ".novabank/env"
        env.mkdir(parents=True)
        (env / "project.env").write_text("BANK_APP_PORT=18080\nSHOP_APP_PORT=18081\n", encoding="utf-8")
        (env / "dev.env").write_text("BANK_API_KEY=fixture-only\nTOTP_ENCRYPTION_KEY=fixture-only\n", encoding="utf-8")
        for directory, artifact in ((self.root, "Bank-demo"), (self.root / "shop-app", "shop-app")):
            (directory / "target").mkdir(parents=True)
            (directory / "src").mkdir()
            (directory / "pom.xml").write_text("fixture", encoding="utf-8")
            jar = directory / "target" / f"{artifact}-fixture.jar"
            jar.write_bytes(b"fixture-not-executable")
            os.utime(jar, (time.time() + 10, time.time() + 10))

    def test_start_never_kills_process_using_port(self):
        self.prepare_app_fixtures()
        with patch("server.java_executable", return_value="fake-java"), patch("server.port_open", return_value=True), patch("server.subprocess.Popen") as spawn, patch("server.stop_process") as stop:
            with self.assertRaisesRegex(RuntimeError, "jest zajęty"):
                self.panel.start_apps("unused")
            spawn.assert_not_called()
            stop.assert_not_called()

    def test_start_rejects_stale_jar(self):
        self.prepare_app_fixtures()
        source = self.root / "src/Fixture.java"
        source.write_text("fixture", encoding="utf-8")
        os.utime(source, (time.time() + 20, time.time() + 20))
        with patch("server.java_executable", return_value="fake-java"), patch("server.subprocess.Popen") as spawn:
            with self.assertRaises(RuntimeError) as caught:
                self.panel.start_apps("unused")
            self.assertEqual("BUILD_REQUIRED", caught.exception.issue_code)
            spawn.assert_not_called()

    def test_test_view_cannot_change_dev_infrastructure(self):
        for action in ("infra-up", "infra-stop", "apps-start", "dev-env"):
            with self.assertRaises(ValueError):
                self.panel.submit(action, "test", True)

    def test_one_job_at_a_time(self):
        gate = threading.Event()
        with patch.object(self.panel, "execute", side_effect=lambda *_: gate.wait(2)):
            job = self.panel.submit("environment-check", "dev", False)
            with self.assertRaises(BusyError):
                self.panel.submit("environment-check", "prod", True, production_confirmation="PROD")
            gate.set()
        self.panel.threads[0].join(2)
        self.panel.jobs[job["id"]]["status"] = "success"

    def test_diagnostic_success_does_not_mean_all_files_exist(self):
        self.panel.jobs["diagnostic"] = {"log": "", "status": "running"}
        self.panel.execute("diagnostic", ACTION_MAP["prod-check"], "prod")
        self.assertEqual("success", self.panel.jobs["diagnostic"]["status"])
        self.assertIn("brak", self.panel.jobs["diagnostic"]["log"])
        self.assertIn("Nie sprawdzono połączeń", self.panel.jobs["diagnostic"]["log"])

    def test_argv_is_allowlisted_no_shell(self):
        command = self.panel.command("infra-up", "dev")
        self.assertEqual(["--web-action", "infra-up", "dev"], command[-3:])
        self.assertNotIn("-c", command)

    def test_non_dev_service_status_never_probes_dev_ports(self):
        with patch("server.port_open") as probe:
            for profile in ("test", "prod"):
                self.assertEqual([], service_status(self.panel, profile)["services"])
            probe.assert_not_called()

    def test_service_state_is_tcp_not_health(self):
        with patch("server.port_open", return_value=True):
            services = service_status(self.panel, "dev")["services"]
        self.assertTrue(all(item["up"] for item in services))
        self.assertTrue(all("nie oznacza gotowości" in item["detail"] for item in services))

    def test_log_is_bounded(self):
        self.panel.jobs["log"] = {"log": "", "status": "success"}
        for _ in range(30):
            self.panel.append("log", "x" * 16_000)
        self.assertLess(len(self.panel.jobs["log"]["log"]), 257_000)

    def test_cancel_unknown_job(self):
        with self.assertRaises(ValueError):
            self.panel.cancel("unknown")

    def test_stop_never_discovers_or_kills_external_apps(self):
        with patch("server.stop_process") as stop:
            self.panel.stop_apps()
            stop.assert_not_called()

    def test_instance_lock_is_exclusive(self):
        path = self.root / "runtime/server.lock"
        first = InstanceLock(path)
        try:
            with self.assertRaises(RuntimeError):
                InstanceLock(path)
        finally:
            first.close()
        second = InstanceLock(path)
        second.close()


class RedactionTests(unittest.TestCase):
    def test_masks_secrets_pii_and_connection_password(self):
        message = "BANK_API_KEY=abcdef TOTP_ENCRYPTION_KEY: hidden123 postgres://user:pass123@localhost/db sk_test_abc123 test@example.com 12345678901234567890123456 unique-known-secret"
        safe = redact(message, ("unique-known-secret",))
        for secret in ("abcdef", "hidden123", "pass123", "sk_test_abc123", "test@example.com", "12345678901234567890123456", "unique-known-secret"):
            self.assertNotIn(secret, safe)

    def test_output_redacts_complete_lines_and_pem(self):
        output = []
        stream = io.BytesIO(b"value=split-secret\n-----BEGIN RSA PRIVATE KEY-----\nnever-visible\n-----END RSA PRIVATE KEY-----\nBUILD SUCCESS\n")
        safe_output(stream, output.append, ("split-secret",))
        self.assertNotIn("split-secret", "".join(output))
        self.assertNotIn("never-visible", "".join(output))
        self.assertIn("BUILD SUCCESS", "".join(output))

    def test_overlong_line_is_dropped(self):
        output = []
        safe_output(io.BytesIO(b"x" * 20_000 + b"\nOK\n"), output.append, ())
        self.assertNotIn("xxx", "".join(output))
        self.assertIn("OK", "".join(output))

    def test_does_not_inherit_prod_config_into_children(self):
        with patch.dict(os.environ, {"SPRING_PROFILES_ACTIVE": "prod", "BANK_API_KEY": "hidden", "JAVA_TOOL_OPTIONS": "unwanted", "PATH": "keep"}):
            env = clean_environment()
        self.assertNotIn("SPRING_PROFILES_ACTIVE", env)
        self.assertNotIn("BANK_API_KEY", env)
        self.assertNotIn("JAVA_TOOL_OPTIONS", env)
        self.assertEqual("keep", env["PATH"])


class HttpTests(unittest.TestCase):
    def test_schedule_updates_and_executions_are_not_cancellable_midway(self):
        from server import ACTION_MAP
        self.assertGreater(ACTION_MAP["backup-schedule-run-now"].timeout, 30 * 60)
        self.assertGreater(ACTION_MAP["dr-schedule-run-now"].timeout, 90 * 60)
        for action in ("backup-schedule-install", "dr-schedule-install", "backup-schedule-run-now", "dr-schedule-run-now"):
            self.panel.jobs["fixture"] = {"action": action, "status": "running"}
            with self.assertRaises(ValueError):
                self.panel.cancel("fixture")
        self.panel.jobs.clear()

    def test_schedule_configuration_alert_is_deduplicated_and_test_never_reads_dev(self):
        headers = {"X-Control-Center-Token": self.panel.token}
        state = {"available": True, "timers": [], "managed": [{"kind": "backup", "configuration": "INVALID", "execution": "NEVER", "lastStarted": None, "lastFinished": None, "exitCode": None}], "note": "fixture"}
        with patch.object(self.panel.legacy_schedules, "state", return_value=state):
            for _ in range(2):
                self.assertEqual(200, self.request("GET", "/api/operations/legacy?profile=dev", extra=headers)[0])
        with self.panel.notifications.connect() as db:
            self.assertEqual(1, db.execute("SELECT count(*) FROM notifications WHERE issue_code = 'SCHEDULE_CONFIGURATION_INVALID'").fetchone()[0])

    def test_read_execution_during_job_never_builds_migration_plans(self):
        self.panel.jobs["fixture"] = {"action": "dr-schedule-run-now", "status": "running"}
        headers = {"X-Control-Center-Token": self.panel.token}
        runtime = {"kind": "dr", "configuration": "VALID", "execution": "RUNNING", "lastStarted": "fixture", "lastFinished": None, "exitCode": None}
        with patch.object(self.panel.legacy_schedules, "state", side_effect=AssertionError("No plans during work")), patch.object(self.panel.legacy_schedules, "runtime", return_value=runtime):
            status, _, raw = self.request("GET", "/api/operations/legacy?profile=dev", extra=headers)
            self.assertEqual(200, status)
            self.assertEqual([], json.loads(raw)["schedules"]["timers"])
            self.assertEqual("RUNNING", json.loads(raw)["schedules"]["managed"][0]["execution"])
        self.panel.jobs.clear()

    def test_operator_reads_require_token_strict_profiles_and_no_get_writes(self):
        headers = {"X-Control-Center-Token": self.panel.token}
        for endpoint in ("settings", "legacy"):
            self.assertEqual(403, self.request("GET", "/api/operations/" + endpoint + "?profile=dev")[0])
            for query in ("", "profile=prod", "profile=dev&profile=dev", "profile=dev&command=whoami", "profile="):
                self.assertEqual(400, self.request("GET", "/api/operations/" + endpoint + "?" + query, extra=headers)[0])
        status, response_headers, raw = self.request("GET", "/api/operations/settings?profile=dev", extra=headers)
        self.assertEqual(200, status)
        self.assertEqual("no-store", response_headers["Cache-Control"])
        self.assertFalse(self.panel.operation_settings.path.exists())
        self.assertEqual("02:00", json.loads(raw)["values"]["backupAt"])
        with patch.object(self.panel.legacy_schedules, "state", return_value={"available": False, "timers": [], "note": "fixture"}):
            self.assertEqual(200, self.request("GET", "/api/operations/legacy?profile=dev", extra=headers)[0])
        with patch.object(self.panel.legacy_schedules, "state", side_effect=AssertionError("TEST must not inspect DEV timers")):
            self.assertEqual(200, self.request("GET", "/api/operations/legacy?profile=test", extra=headers)[0])

    def test_operator_save_is_confirmed_versioned_and_blocked_during_job(self):
        current = self.panel.operation_settings.state("dev")
        payload = {"profile": "dev", "revision": current["revision"], "values": current["values"] | {"backupAt": "23:20"}, "confirmed": True}
        self.assertEqual(400, self.request("POST", "/api/operations/settings", payload | {"confirmed": False})[0])
        self.assertEqual(400, self.request("POST", "/api/operations/settings", payload | {"profile": "prod"})[0])
        self.panel.jobs["fixture"] = {"status": "running"}
        self.assertEqual(409, self.request("POST", "/api/operations/settings", payload)[0])
        self.panel.jobs.clear()
        self.assertEqual(200, self.request("POST", "/api/operations/settings", payload)[0])
        self.assertEqual(409, self.request("POST", "/api/operations/settings", payload)[0])
        self.assertEqual("23:20", self.panel.operation_settings.state("dev")["values"]["backupAt"])
        self.assertFalse((self.panel.root / ".novabank/backups/dev").exists())

    def test_migration_job_requires_dev_confirmation_revision_and_never_exposes_plan_parameter(self):
        payload = {"action": "migrate-cli-backup", "profile": "dev", "confirmed": True, "parameters": {"revision": "a" * 64}}
        self.assertEqual(400, self.request("POST", "/api/jobs", payload | {"confirmed": False})[0])
        self.assertEqual(400, self.request("POST", "/api/jobs", payload | {"profile": "test"})[0])
        self.assertEqual(400, self.request("POST", "/api/jobs", payload | {"parameters": {"revision": "bad"}})[0])
        with patch.object(self.panel.legacy_schedules, "migrate") as migrate:
            status, _, raw = self.request("POST", "/api/jobs", payload)
            self.assertEqual(202, status)
            self.panel.threads[-1].join(3)
            self.assertNotIn(b"a" * 64, raw)
            migrate.assert_called_once()
            self.assertEqual("success", self.panel.jobs[json.loads(raw)["id"]]["status"])
        for path in ("/operator-settings.js", "/operator-settings.css"):
            self.assertEqual(200, self.request("GET", path)[0])
        self.assertEqual(404, self.request("GET", "/operator_settings.py")[0])

    def test_shop_account_reads_require_token_dev_and_strict_filters(self):
        with patch.object(self.panel, "shop_accounts", side_effect=AssertionError("no DB or env access")):
            self.assertEqual(403, self.request("GET", "/api/shop-accounts?profile=dev")[0])
            headers = {"X-Control-Center-Token": self.panel.token}
            for query in ("", "profile=test", "profile=prod", "profile=dev&profile=dev", "profile=dev&q=a&q=b", "profile=dev&sql=anything", "profile=dev&before=", "profile=dev&role=USER", "profile=dev&status=BLOCKED"):
                self.assertEqual(400, self.request("GET", "/api/shop-accounts?" + query, extra=headers)[0])
            for query in ("profile=prod", "profile=dev&role=ALL"):
                self.assertEqual(400, self.request("GET", "/api/shop-accounts/1?" + query, extra=headers)[0])
            self.assertEqual(400, self.request("GET", "/api/shop-accounts/9223372036854775808?profile=dev", extra=headers)[0])
            self.assertEqual(403, self.request("GET", "/api/shop-accounts?profile=dev", extra=headers | {"Origin": "https://evil.example"})[0])

    def test_payment_cases_require_token_dev_closed_parameters_and_no_get_mutation(self):
        headers={'X-Control-Center-Token':self.panel.token}
        with patch.object(self.panel,'payment_cases',side_effect=AssertionError('No data access before validation')):
            self.assertEqual(403,self.request('GET','/api/payment-cases?profile=dev')[0])
            for query in ('profile=prod','profile=test','profile=dev&sql=anything','profile=dev&view=DROP','profile=dev&profile=dev','profile=dev&q=a&q=b'):
                self.assertEqual(400,self.request('GET','/api/payment-cases?'+query,extra=headers)[0])
            self.assertEqual(400,self.request('GET','/api/payment-cases/9223372036854775808?profile=dev',extra=headers)[0])
            self.assertEqual(403,self.request('GET','/api/payment-cases?profile=dev',extra=headers|{'Origin':'https://evil.example'})[0])
        with patch.object(self.panel,'payment_cases') as factory:
            factory.return_value.listing.return_value={'cases':[],'next':None,'profile':'dev'}
            factory.return_value.detail.return_value={'id':'1','outcome':'UNKNOWN'}
            self.assertEqual(200,self.request('GET','/api/payment-cases?profile=dev',extra=headers)[0])
            self.assertEqual(200,self.request('GET','/api/payment-cases/1?profile=dev',extra=headers)[0])
            self.assertEqual(404,self.request('POST','/api/payment-cases',{})[0])
        for asset in ('/payment-cases.js','/payment-cases.css'):self.assertEqual(200,self.request('GET',asset)[0])

    def test_shop_account_read_only_contract_and_no_mutation_route(self):
        headers = {"X-Control-Center-Token": self.panel.token}
        with patch.object(self.panel, "shop_accounts") as factory:
            reader = factory.return_value
            reader.listing.return_value = {"accounts": [], "next": None, "pageSize": 50, "readOnly": True}
            status, response_headers, raw = self.request("GET", "/api/shop-accounts?profile=dev&q=demo&status=ALL&role=ADMIN", extra=headers)
            self.assertEqual(200, status)
            self.assertEqual("no-store", response_headers["Cache-Control"])
            self.assertTrue(json.loads(raw)["readOnly"])
            reader.listing.assert_called_once_with({"q": "demo", "status": "ALL", "role": "ADMIN", "before": None})
            reader.detail.return_value = {"account": {"id": "9007199254740993"}, "readOnly": True}
            raw = self.request("GET", "/api/shop-accounts/9007199254740993?profile=dev", extra=headers)[2]
            self.assertEqual("9007199254740993", json.loads(raw)["account"]["id"])
            reader.detail.side_effect = LookupError("Nie znaleziono konta sklepu DEV.")
            self.assertEqual(404, self.request("GET", "/api/shop-accounts/1?profile=dev", extra=headers)[0])
            self.assertEqual(404, self.request("POST", "/api/shop-accounts", {"sql": "anything"})[0])
        self.assertEqual(200, self.request("GET", "/shop-accounts.js")[0])
        self.assertEqual(404, self.request("GET", "/dev_shop_accounts.py")[0])

    def test_shop_read_failures_are_private_and_share_bounded_reads(self):
        headers = {"X-Control-Center-Token": self.panel.token}
        with patch.object(self.panel, "shop_accounts", side_effect=RuntimeError("fixture-private-password")):
            status, _, raw = self.request("GET", "/api/shop-accounts?profile=dev&q=private-shop-customer", extra=headers)
        self.assertEqual(503, status)
        self.assertEqual("SHOP_ACCOUNTS_READ_FAILED", json.loads(raw)["issueCode"])
        self.assertNotIn(b"fixture-private-password", raw)
        self.assertNotIn("private-shop-customer", json.dumps(self.panel.notifications.page()))
        self.panel.account_reads.acquire(); self.panel.account_reads.acquire()
        try:
            with patch.object(self.panel, "shop_accounts", side_effect=AssertionError("no DB")):
                self.assertEqual(503, self.request("GET", "/api/shop-accounts?profile=dev", extra=headers)[0])
        finally:
            self.panel.account_reads.release(); self.panel.account_reads.release()

    def test_bank_account_reads_require_token_dev_and_strict_filters(self):
        with patch.object(self.panel, "cash", side_effect=AssertionError("no DB access")):
            self.assertEqual(403, self.request("GET", "/api/bank-accounts?profile=dev")[0])
            headers = {"X-Control-Center-Token": self.panel.token}
            for query in ("", "profile=test", "profile=prod", "profile=dev&profile=dev", "profile=dev&q=a&q=b", "profile=dev&sql=anything", "profile=dev&before=", "profile=dev&currency=", "profile=dev&status=bad"):
                self.assertEqual(400, self.request("GET", "/api/bank-accounts?" + query, extra=headers)[0])
            for query in ("profile=prod", "profile=dev&q=anything"):
                self.assertEqual(400, self.request("GET", "/api/bank-accounts/1?" + query, extra=headers)[0])
            self.assertEqual(400, self.request("GET", "/api/bank-accounts/9223372036854775808?profile=dev", extra=headers)[0])
            self.assertEqual(403, self.request("GET", "/api/bank-accounts?profile=dev", extra=headers | {"Origin": "https://evil.example"})[0])

    def test_bank_account_read_only_contract_and_no_mutation_route(self):
        headers = {"X-Control-Center-Token": self.panel.token}
        with patch("server.DevAccounts") as reader, patch.object(self.panel, "cash"):
            reader.return_value.listing.return_value = {"accounts": [], "next": None, "pageSize": 50, "readOnly": True}
            status, response_headers, raw = self.request("GET", "/api/bank-accounts?profile=dev&q=demo&status=ALL&currency=PLN", extra=headers)
            self.assertEqual(200, status)
            self.assertEqual("no-store", response_headers["Cache-Control"])
            self.assertTrue(json.loads(raw)["readOnly"])
            reader.return_value.detail.return_value = {"account": {"id": "9007199254740993"}, "readOnly": True}
            raw = self.request("GET", "/api/bank-accounts/9007199254740993?profile=dev", extra=headers)[2]
            self.assertEqual("9007199254740993", json.loads(raw)["account"]["id"])
            reader.return_value.detail.side_effect = LookupError("Nie znaleziono rachunku DEV.")
            self.assertEqual(404, self.request("GET", "/api/bank-accounts/1?profile=dev", extra=headers)[0])
            self.assertEqual(404, self.request("POST", "/api/bank-accounts", {"sql": "anything"})[0])
        for path in ("/bank-accounts.js", "/bank-accounts.css"):
            self.assertEqual(200, self.request("GET", path)[0])

    def test_bank_account_failure_notification_is_safe_and_parallel_reads_bounded(self):
        headers = {"X-Control-Center-Token": self.panel.token}
        with patch.object(self.panel, "cash", side_effect=RuntimeError("fixture-private-password")):
            status, _, raw = self.request("GET", "/api/bank-accounts?profile=dev&q=private-name", extra=headers)
        self.assertEqual(503, status)
        result = json.loads(raw)
        self.assertEqual("ACCOUNTS_READ_FAILED", result["issueCode"])
        self.assertNotIn(b"fixture-private-password", raw)
        self.assertNotIn("private-name", json.dumps(self.panel.notifications.page()))
        self.panel.account_reads.acquire(); self.panel.account_reads.acquire()
        try:
            with patch.object(self.panel, "cash", side_effect=AssertionError("no DB")):
                self.assertEqual(503, self.request("GET", "/api/bank-accounts?profile=dev", extra=headers)[0])
        finally:
            self.panel.account_reads.release(); self.panel.account_reads.release()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.panel = Panel(Path(self.temp.name))
        self.server = Server(0, self.panel)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)
        self.panel.close()
        self.temp.cleanup()

    def request(self, method, path, payload=None, extra=None):
        headers = {"Host": f"127.0.0.1:{self.port}"}
        body = None
        if payload is not None:
            body = json.dumps(payload)
            headers.update({"Content-Type": "application/json", "X-Control-Center-Token": self.panel.token})
        headers.update(extra or {})
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        data = response.read()
        result = response.status, dict(response.getheaders()), data
        connection.close()
        return result

    def test_retention_requires_trusted_request_and_explicit_export(self):
        payload = {"operation": "plan", "profile": "dev", "trashDays": 30, "archiveDays": 180}
        for extra in ({"X-Control-Center-Token": "bad"}, {"Origin": "https://untrusted.example"}):
            self.assertEqual(403, self.request("POST", "/api/notifications/retention", payload, extra)[0])
        self.assertEqual(400, self.request("POST", "/api/notifications/retention", payload | {"profile": "prod"})[0])
        self.assertEqual(400, self.request("POST", "/api/notifications/retention", payload | {"path": "/tmp"})[0])
        status, _, raw = self.request("POST", "/api/notifications/retention", payload)
        self.assertEqual(200, status)
        plan = json.loads(raw)
        purge = {"operation": "purge", "profile": "dev", "planId": plan["planId"],
                 "confirmed": True, "acknowledgement": "USUN"}
        self.assertEqual(400, self.request("POST", "/api/notifications/retention", purge)[0])
        self.assertEqual(200, self.request("POST", "/api/notifications/retention",
                {"operation": "export", "profile": "dev", "planId": plan["planId"]})[0])
        self.assertEqual(200, self.request("POST", "/api/notifications/retention", purge)[0])

    def test_page_has_security_headers(self):
        status, headers, data = self.request("GET", "/")
        self.assertEqual(200, status)
        self.assertEqual("DENY", headers["X-Frame-Options"])
        self.assertIn("frame-ancestors 'none'", headers["Content-Security-Policy"])
        self.assertIn(b"NovaBank", data)

    def test_support_is_authenticated_bounded_and_not_log_classified(self):
        headers = {"X-Control-Center-Token": self.panel.token}
        ident = self.panel.notify_problem("CONFIG_FILES_MISSING", "test")
        self.assertEqual(403, self.request("GET", f"/api/support/{ident}")[0])
        self.assertEqual(400, self.request("GET", f"/api/support/{ident}?path=anything", extra=headers)[0])
        status, _, data = self.request("GET", f"/api/support/{ident}", extra=headers)
        self.assertEqual(200, status)
        info = json.loads(data)
        self.assertEqual("test-env", info["actions"][0]["id"])
        self.assertIsNone(info["plan"])
        self.assertEqual(403, self.request("POST", "/api/support/check", {"id": ident}, {"Origin": "https://evil.example"})[0])
        self.assertEqual(400, self.request("POST", "/api/support/check", {"id": ident, "command": "anything"})[0])
        self.assertEqual(200, self.request("POST", "/api/support/check", {"id": ident})[0])
        self.assertFalse((self.panel.root / ".novabank/env/test.env").exists())
        self.assertEqual(200, self.request("GET", "/problem-ui.js")[0])
        for path in ("/problem_support.py", "/problems.py", "/.novabank/control-center/web/repair-backups/x"):
            self.assertEqual(404, self.request("GET", path)[0])

    def test_stale_configuration_response_links_to_structured_notification(self):
        state = self.panel.configuration_files.state("test")
        self.panel.configuration_files.save("test", "test", state["revision"], {"TEST_BANK_API_KEY": "fixture-before"}, [], True, False)
        body = {"profile": "test", "file": "test", "revision": state["revision"], "values": {"TEST_BANK_API_KEY": "fixture-pending"}, "remove": [], "confirmed": True, "acknowledgeRisk": True}
        status, _, data = self.request("POST", "/api/config/files/save", body)
        self.assertEqual(409, status)
        error = json.loads(data)
        self.assertEqual("CONFIG_REVISION_CHANGED", error["issueCode"])
        event = self.panel.notifications.detail(error["notificationId"])
        self.assertEqual("CONFIG_REVISION_CHANGED", event["issue_code"])
        self.assertNotIn(b"fixture-pending", data)

    def test_http_support_plan_confirmed_job_and_recheck_are_separate_from_archive(self):
        import shutil
        import uuid
        from dev_configuration import upsert
        from problem_support import REPAIR_ACTION
        self.panel.dev_configuration.save(self.panel.dev_configuration.state()["revision"], {}, True)
        dev = self.panel.root / ".novabank/env/dev.env"
        dev.write_bytes(upsert(dev.read_bytes(), {"METRICS_PASSWORD": "fixture-dev-existing-password"}))
        monitoring = self.panel.root / ".novabank/monitoring"
        monitoring.mkdir()
        (monitoring / ".env.monitoring").write_text("METRICS_USERNAME=old\nMETRICS_PASSWORD=fixture-old-password\n")
        template = Path(__file__).resolve().parents[3] / "infra/monitoring/prometheus/prometheus.yml.example"
        shutil.copyfile(template, monitoring / "prometheus.yml")
        ident = self.panel.notify_problem("METRICS_CREDENTIALS_CONFLICT", "dev")
        headers = {"X-Control-Center-Token": self.panel.token}
        status, _, raw = self.request("GET", f"/api/support/{ident}", extra=headers)
        self.assertEqual(200, status)
        plan = json.loads(raw)["plan"]
        params = {"notificationId": ident, "requestId": str(uuid.uuid4()), "revision": plan["revision"], "planToken": plan["planToken"]}
        payload = {"action": REPAIR_ACTION, "profile": "dev", "confirmed": False, "parameters": params}
        self.assertEqual(400, self.request("POST", "/api/jobs", payload)[0])
        status, _, raw = self.request("POST", "/api/jobs", payload | {"confirmed": True})
        self.assertEqual(202, status)
        job_id = json.loads(raw)["id"]
        self.panel.threads[-1].join(3)
        self.assertEqual("success", self.panel.jobs[job_id]["status"])
        replay = json.loads(self.request("POST", "/api/jobs", payload | {"confirmed": True})[2])
        self.assertEqual(job_id, replay["id"])
        status, _, raw = self.request("POST", "/api/support/check", {"id": ident})
        self.assertEqual(200, status)
        result = json.loads(raw)
        self.assertEqual("configuration_verified", result["outcome"])
        self.assertIsNone(result["plan"])
        self.assertEqual("active", self.panel.notifications.detail(ident)["state"])
        self.assertNotIn(b"fixture-dev-existing-password", raw)
        self.assertNotIn("parameters", replay)

    def test_file_editor_read_write_reveal_profile_and_token_gates(self):
        headers = {"X-Control-Center-Token": self.panel.token}
        self.assertEqual(403, self.request("GET", "/api/config/files?profile=dev")[0])
        for query in ("profile=prod", "profile=dev&profile=test", "profile=dev&path=x", "profile="):
            self.assertEqual(400, self.request("GET", "/api/config/files?" + query, extra=headers)[0])
        state = json.loads(self.request("GET", "/api/config/files?profile=dev", extra=headers)[2])
        payload = {"profile": "dev", "file": "dev", "revision": state["revision"], "confirmed": True,
                   "values": {"BANK_API_KEY": "fixture-sensitive-value"}, "remove": [], "acknowledgeRisk": False}
        self.assertEqual(403, self.request("POST", "/api/config/files/save", payload, {"Origin": "https://evil.example"})[0])
        self.assertEqual(403, self.request("POST", "/api/config/files/save", payload, {"X-Control-Center-Token": "bad"})[0])
        self.assertEqual(400, self.request("POST", "/api/config/files/save", payload | {"confirmed": False})[0])
        status, headers, body = self.request("POST", "/api/config/files/save", payload)
        self.assertEqual(200, status)
        self.assertNotIn(b"fixture-sensitive-value", body)
        self.assertEqual("no-store", headers["Cache-Control"])
        state = json.loads(body)
        reveal = {"profile": "dev", "file": "dev", "revision": state["revision"], "key": "BANK_API_KEY", "confirmed": True}
        self.assertEqual(400, self.request("POST", "/api/config/files/reveal", reveal | {"confirmed": False})[0])
        self.assertEqual(400, self.request("POST", "/api/config/files/reveal", reveal | {"profile": "test"})[0])
        self.assertEqual(200, self.request("POST", "/api/config/files/reveal", reveal)[0])
        self.assertNotIn("fixture-sensitive-value", json.dumps(self.panel.public_jobs()))
        self.assertNotIn("fixture-sensitive-value", json.dumps(self.panel.notifications.page()))

    def test_editor_busy_and_errors_do_not_leak_values(self):
        state = self.panel.configuration_files.state("test")
        payload = {"profile": "test", "file": "test", "revision": state["revision"], "confirmed": True,
                   "values": {"TEST_BANK_API_KEY": "fixture-only-sensitive"}, "remove": [], "acknowledgeRisk": False}
        self.panel.jobs["fixture"] = {"status": "running"}
        self.assertEqual(409, self.request("POST", "/api/config/files/save", payload)[0])
        self.panel.jobs.clear()
        with patch.object(self.panel.configuration_files, "save", side_effect=OSError("fixture-only-sensitive")):
            status, _, body = self.request("POST", "/api/config/files/save", payload)
        self.assertEqual(503, status)
        self.assertNotIn(b"fixture-only-sensitive", body)

    def test_test_env_generation_is_stable_and_does_not_create_dev(self):
        job = self.panel.submit("test-env", "test", True)
        self.panel.threads[-1].join(3)
        self.assertEqual("success", self.panel.jobs[job["id"]]["status"])
        raw = (self.panel.root / ".novabank/env/test.env").read_bytes()
        job2 = self.panel.submit("test-env", "test", True)
        self.panel.threads[-1].join(3)
        self.assertEqual(raw, (self.panel.root / ".novabank/env/test.env").read_bytes())
        self.assertFalse((self.panel.root / ".novabank/env/dev.env").exists())

    def test_openapi_and_catalog_are_local_read_only_and_secret_free(self):
        with patch.object(self.panel, "known_secrets", side_effect=AssertionError("must not read secrets")), \
             patch.object(self.panel, "submit", side_effect=AssertionError("must not submit")), \
             patch.object(self.panel.notifications, "page", side_effect=AssertionError("must not collect logs")):
            status, headers, data = self.request("GET", "/api/openapi.json")
            self.assertEqual(200, status)
            self.assertEqual("no-store", headers["Cache-Control"])
            spec = json.loads(data)
            self.assertEqual("3.1.0", spec["openapi"])
            self.assertEqual("/", spec["servers"][0]["url"])
            self.assertNotIn(self.panel.token.encode(), data)
            self.assertEqual(25, len(spec["paths"]))
            self.assertEqual(28, sum(len(methods) for methods in spec["paths"].values()))
            self.assertNotIn("Access-Control-Allow-Origin", headers)
            for resource in ("/docs/api", "/api-docs.js"):
                self.assertEqual(200, self.request("GET", resource)[0])
        for extra in ({"Host": "evil.example"}, {"Origin": "https://evil.example"}, {"Sec-Fetch-Site": "cross-site"}):
            self.assertEqual(403, self.request("GET", "/api/openapi.json", extra=extra)[0])
            self.assertEqual(403, self.request("GET", "/docs/api", extra=extra)[0])

    def test_bootstrap_documentation_links_use_only_valid_local_dev_ports(self):
        env = self.panel.root / ".novabank/env"
        env.mkdir(parents=True)
        (env / "project.env").write_text("BANK_APP_PORT=18080\nSHOP_APP_PORT=18081\n", encoding="utf-8")
        (env / "dev.env").write_text("BANK_APP_PORT=19080\nBANK_API_KEY=fixture-private-value\n", encoding="utf-8")
        status, _, data = self.request("GET", "/api/bootstrap")
        self.assertEqual(200, status)
        links = json.loads(data)["documentation"]
        self.assertEqual("http://localhost:19080/swagger-ui/index.html", links["bank"]["swagger"])
        self.assertEqual("http://localhost:18081/v3/api-docs", links["shop"]["openapi"])
        self.assertNotIn(b"fixture-private-value", data)
        (env / "dev.env").write_text("BANK_APP_PORT=https://evil.example\nSHOP_APP_PORT=65536\n", encoding="utf-8")
        data = json.loads(self.request("GET", "/api/bootstrap")[2])
        self.assertIsNone(data["documentation"]["bank"]["swagger"])
        self.assertIsNone(data["documentation"]["shop"]["openapi"])

    def test_default_documentation_links_and_page_links(self):
        data = json.loads(self.request("GET", "/api/bootstrap")[2])
        self.assertEqual("http://localhost:8080/v3/api-docs", data["documentation"]["bank"]["openapi"])
        self.assertEqual("http://localhost:8081/swagger-ui/index.html", data["documentation"]["shop"]["swagger"])
        page = self.request("GET", "/")[2].decode()
        for text in ("Swagger banku", "Swagger sklepu", 'href="/docs/api"', 'href="/api/openapi.json"'):
            self.assertIn(text, page)

    def test_configuration_read_and_write_are_dev_only_and_token_protected(self):
        path = "/api/config/dev?profile=dev"
        self.assertEqual(403, self.request("GET", path)[0])
        headers = {"X-Control-Center-Token": self.panel.token}
        for query in ("", "?profile=prod", "?profile=test", "?profile=dev&path=other", "?profile=dev&profile=dev", "?profile=dev&ignored="):
            self.assertEqual(400, self.request("GET", "/api/config/dev" + query, extra=headers)[0])
        state = json.loads(self.request("GET", path, extra=headers)[2])
        payload = {"profile": "dev", "revision": state["revision"], "values": {}, "confirmed": True}
        for extra in ({"X-Control-Center-Token": ""}, {"Origin": "https://evil.example"}, {"Sec-Fetch-Site": "cross-site"}):
            self.assertEqual(403, self.request("POST", "/api/config/dev", payload, extra)[0])
        for change in ({"profile": "prod"}, {"profile": "test"}, {"confirmed": False}, {"confirmed": "true"}, {"path": "other"}, {"values": {"BANK_API_KEY": "fixture-replacement"}}):
            self.assertEqual(400, self.request("POST", "/api/config/dev", payload | change)[0])
        self.assertFalse(self.panel.dev_configuration.directory.exists())

    def test_configuration_save_has_no_secrets_in_response_and_next_read(self):
        headers = {"X-Control-Center-Token": self.panel.token}
        state = json.loads(self.request("GET", "/api/config/dev?profile=dev", extra=headers)[2])
        payload = {"profile": "dev", "revision": state["revision"], "values": {"POSTGRES_PASSWORD": "new-fixture-password", "BANK_APP_PORT": "18080"}, "confirmed": True}
        status, _, data = self.request("POST", "/api/config/dev", payload)
        self.assertEqual(200, status)
        self.assertNotIn(b"new-fixture-password", data)
        self.assertTrue(json.loads(data)["ready"])
        self.assertEqual(409, self.request("POST", "/api/config/dev", payload)[0])
        for name in ("project", "dev"):
            self.assertTrue((self.panel.dev_configuration.directory / f"{name}.env").is_file())
        read = self.request("GET", "/api/config/dev?profile=dev", extra=headers)[2]
        self.assertNotIn(b"new-fixture-password", read)

    def test_configuration_save_is_blocked_while_a_job_runs(self):
        state = self.panel.dev_configuration.state()
        payload = {"profile": "dev", "revision": state["revision"], "values": {}, "confirmed": True}
        self.panel.jobs["fixture"] = {"id": "fixture", "status": "running", "action": "unit-bank", "log": ""}
        try:
            self.assertEqual(409, self.request("POST", "/api/config/dev", payload)[0])
            self.assertFalse(self.panel.dev_configuration.directory.exists())
        finally:
            self.panel.jobs.clear()

    def test_configuration_system_failure_creates_safe_notification(self):
        payload = {"profile": "dev", "revision": self.panel.dev_configuration.state()["revision"], "values": {}, "confirmed": True}
        with patch.object(self.panel.dev_configuration, "save", side_effect=OSError("fixture-private-data")):
            status, _, data = self.request("POST", "/api/config/dev", payload)
        self.assertEqual(503, status)
        self.assertNotIn(b"fixture-private-data", data)
        items = self.panel.notifications.page()["items"]
        self.assertTrue(any(item["level"] == "ERROR" for item in items))
        self.assertNotIn("fixture-private-data", json.dumps(items))

    def test_unknown_host_blocks_dns_rebinding(self):
        self.assertEqual(403, self.request("GET", "/api/bootstrap", extra={"Host": "evil.example"})[0])

    def test_cross_origin_requests_blocked(self):
        for extra in ({"Origin": "https://evil.example"}, {"Sec-Fetch-Site": "cross-site"}):
            self.assertEqual(403, self.request("GET", "/api/bootstrap", extra=extra)[0])

    def test_post_without_token_rejected(self):
        payload = {"action": "environment-check", "profile": "dev", "confirmed": False}
        self.assertEqual(403, self.request("POST", "/api/jobs", payload, {"X-Control-Center-Token": ""})[0])

    def test_accounts_and_notifications_require_token(self):
        for path in ("/api/accounts?profile=dev", "/api/notifications", "/api/notifications/1"):
            self.assertEqual(403, self.request("GET", path)[0])

    def test_notification_actions_require_token_and_trusted_origin(self):
        self.panel.notifications.add("Bank", "WARN", "Fixture", fingerprint="alert")
        notification_id = self.panel.notifications.page()["items"][0]["id"]
        payload = {"operation": "delete", "id": notification_id, "confirmed": True}
        for headers in ({"X-Control-Center-Token": ""}, {"Origin": "https://evil.example"}, {"Sec-Fetch-Site": "cross-site"}):
            self.assertEqual(403, self.request("POST", "/api/notifications/action", payload, headers)[0])
        self.assertEqual("active", self.panel.notifications.detail(notification_id)["state"])

    def test_notification_archive_delete_restore_flow_and_bulk_safety(self):
        self.panel.notifications.add("Bank", "WARN", "Fixture", fingerprint="alert")
        notification_id = self.panel.notifications.page()["items"][0]["id"]
        headers = {"X-Control-Center-Token": self.panel.token}
        self.assertEqual(200, self.request("GET", f"/api/notifications/{notification_id}", extra=headers)[0])
        payload = {"operation": "archive", "id": notification_id, "confirmed": False}
        self.assertEqual(200, self.request("POST", "/api/notifications/action", payload)[0])
        self.assertEqual(400, self.request("POST", "/api/notifications/action", payload | {"operation": "delete"})[0])
        self.assertEqual(200, self.request("POST", "/api/notifications/action", payload | {"operation": "delete", "confirmed": True})[0])
        self.assertEqual(200, self.request("POST", "/api/notifications/action", payload | {"operation": "restore"})[0])
        self.assertEqual("archived", self.panel.notifications.detail(notification_id)["state"])
        self.assertEqual(200, self.request("POST", "/api/notifications/action", {"operation": "delete-active", "confirmed": True})[0])
        self.assertEqual("archived", self.panel.notifications.detail(notification_id)["state"])

    def test_notification_api_rejects_bad_inputs_and_missing_notification(self):
        for payload in ({"operation": "purge", "id": 1, "confirmed": True}, {"operation": "delete", "id": True, "confirmed": True},
                        {"operation": "delete-active", "id": 1, "confirmed": True}, {"operation": "archive", "confirmed": False},
                        {"operation": "delete-active", "confirmed": "true"}, {"operation": "delete-active", "confirmed": True, "sql": "DELETE"}):
            self.assertEqual(400, self.request("POST", "/api/notifications/action", payload)[0])
        self.assertEqual(404, self.request("POST", "/api/notifications/action", {"operation": "archive", "id": 999, "confirmed": False})[0])
        self.assertEqual(400, self.request("GET", "/api/notifications?view=unknown", extra={"X-Control-Center-Token": self.panel.token})[0])

    def test_accounts_only_dev_and_notifications_cursor_validated(self):
        headers = {"X-Control-Center-Token": self.panel.token}
        for profile in ("test", "prod"):
            self.assertEqual(400, self.request("GET", "/api/accounts?profile=" + profile, extra=headers)[0])
        self.assertEqual(200, self.request("GET", "/api/notifications", extra=headers)[0])
        for suffix in ("?before=-1", "?before=abc", "?before=1&before=2", "?command=whoami", "?before=999999999999999999999"):
            self.assertEqual(400, self.request("GET", "/api/notifications" + suffix, extra=headers)[0])

    def test_cash_api_rejects_invalid_parameters_and_prod(self):
        parameters = {"accountId": 1, "amount": "1.00", "requestId": "35c3d10c-7a2c-4e15-a5b8-4b47631c0dd5"}
        payload = {"action": "cash-debit", "profile": "dev", "confirmed": True, "parameters": parameters}
        with patch("server.DevCash.adjust") as adjust:
            for changed in ({"profile": "prod"}, {"confirmed": False}, {"parameters": parameters | {"amount": "-1"}}, {"parameters": parameters | {"accountId": True}}):
                self.assertEqual(400, self.request("POST", "/api/jobs", payload | changed)[0])
            adjust.assert_not_called()

    def test_post_with_token_still_blocks_cross_origin(self):
        payload = {"action": "environment-check", "profile": "dev", "confirmed": False}
        self.assertEqual(403, self.request("POST", "/api/jobs", payload, {"Origin": "https://evil.example"})[0])

    def test_prod_mutation_rejected_server_side(self):
        payload = {"action": "infra-up", "profile": "prod", "confirmed": True}
        self.assertEqual(400, self.request("POST", "/api/jobs", payload)[0])

    def test_confirmation_type_is_not_truthy_string(self):
        payload = {"action": "apps-start", "profile": "dev", "confirmed": "true"}
        self.assertEqual(400, self.request("POST", "/api/jobs", payload)[0])

    def test_arbitrary_command_field_rejected(self):
        payload = {"action": "environment-check", "profile": "dev", "confirmed": False, "command": "whoami"}
        self.assertEqual(404, self.request("POST", "/api/jobs", payload)[0])

    def test_secret_source_and_traversal_not_served(self):
        for path in ("/server.py", "/test_server.py", "/.novabank/env/dev.env", "/../.env", "/%2e%2e/.env"):
            self.assertEqual(404, self.request("GET", path)[0])

    def test_report_job_completes_with_real_log(self):
        status, _, data = self.request("POST", "/api/jobs", {"action": "environment-check", "profile": "prod", "confirmed": True, "productionConfirmation": "PROD"})
        self.assertEqual(202, status)
        job_id = json.loads(data)["id"]
        self.panel.threads[-1].join(3)
        status, _, data = self.request("GET", f"/api/jobs/{job_id}")
        self.assertEqual(200, status)
        job = json.loads(data)
        self.assertEqual("success", job["status"])
        self.assertIn("PROD", job["log"])

    def test_prod_http_requires_confirmation_and_never_returns_private_inputs(self):
        payload = {"action": "vault-setup", "profile": "prod", "confirmed": True, "parameters": {"vaultToken": "fixture-http-private-token"}}
        with patch.object(self.panel.production, "run") as execute:
            for changed in ({}, {"productionConfirmation": "prod"}, {"productionConfirmation": "PROD", "confirmed": False}, {"profile": "dev", "productionConfirmation": "PROD"}):
                self.assertEqual(400, self.request("POST", "/api/jobs", payload | changed)[0])
            execute.assert_not_called()
            status, _, data = self.request("POST", "/api/jobs", payload | {"productionConfirmation": "PROD"})
            self.assertEqual(202, status)
            self.assertNotIn(b"fixture-http-private-token", data)
            self.panel.threads[-1].join(3)
            execute.assert_called_once()
            status, _, body = self.request("GET", "/api/jobs/" + json.loads(data)["id"])
            self.assertEqual(200, status)
            self.assertNotIn(b"fixture-http-private-token", body)

    def test_production_assets_are_served_but_modules_and_secret_sources_are_not(self):
        for asset in ("production-ui.js", "guided-ui.js", "panel-language.js"):
            self.assertEqual(200, self.request("GET", "/" + asset)[0])
        for source in ("production_operations.py", ".novabank/env/prod.bank.env", "infra/vault/policies/control-center-operator.hcl"):
            self.assertEqual(404, self.request("GET", "/" + source)[0])


if __name__ == "__main__":
    unittest.main()
