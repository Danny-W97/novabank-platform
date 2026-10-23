"""Local command scope and Stripe adapter checks, all mocked/offline."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch, Mock

from server import ACTIONS, ACTION_MAP, Panel
from stripe_local import run, login


class LocalOperationsTests(unittest.TestCase):
    def test_every_new_operation_requires_profile_gate_and_no_parameters(self):
        from local_operations import EXTENDED_ACTIONS
        with tempfile.TemporaryDirectory() as directory:
            panel = Panel(Path(directory))
            for item in EXTENDED_ACTIONS:
                action = ACTION_MAP[item[0]]
                with self.assertRaises(ValueError):
                    panel.submit(action.id, "prod", True)
                with self.assertRaises(ValueError):
                    panel.submit(action.id, action.profiles[0], True, {"command": "whoami"})
                if action.changes:
                    with self.assertRaises(ValueError):
                        panel.submit(action.id, action.profiles[0], False)
            panel.close()

    def test_api_rotation_is_not_logged_and_keeps_totp(self):
        with tempfile.TemporaryDirectory() as directory:
            panel = Panel(Path(directory))
            panel.dev_configuration.save(panel.dev_configuration.state()["revision"], {}, True)
            from dev_configuration import assignments
            path = Path(directory) / ".novabank/env/dev.env"
            before = assignments(path.read_bytes())
            job = panel.submit("api-key-rotate", "dev", True)
            panel.threads[-1].join(3)
            after = assignments(path.read_bytes())
            self.assertEqual("success", panel.jobs[job["id"]]["status"])
            self.assertNotEqual(before["BANK_API_KEY"], after["BANK_API_KEY"])
            self.assertEqual(before["TOTP_ENCRYPTION_KEY"], after["TOTP_ENCRYPTION_KEY"])
            self.assertNotIn(after["BANK_API_KEY"], panel.jobs[job["id"]]["log"])
            panel.close()

    def test_test_runner_receives_only_valid_test_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            panel = Panel(Path(directory))
            state = panel.configuration_files.state("test")
            panel.configuration_files.save("test", "test", state["revision"], {"TEST_BANK_API_KEY": "fixture-only-key"}, [], True, False)
            panel.jobs["fixture"] = {"profile": "test", "status": "running", "log": ""}
            process = Mock()
            process.stdout.readline.return_value = b""
            process.wait.return_value = 0
            with patch("server.subprocess.Popen", return_value=process) as spawn, patch("server.java_executable", return_value=None):
                import time
                panel.run_command("fixture", ["fake-maven"], time.monotonic() + 10, ())
            self.assertEqual("fixture-only-key", spawn.call_args.kwargs["env"]["TEST_BANK_API_KEY"])
            panel.jobs.clear()
            panel.close()

    def test_stripe_secret_capture_is_private_and_uses_env_not_argv(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / ".novabank/env/dev.env"
            path.parent.mkdir(parents=True)
            path.write_text("SHOP_PAYMENT_API_KEY=sk_test_fixture\nSHOP_APP_PORT=18081\nSHOP_PAYMENT_PROVIDER=stripe\n")
            response = subprocess.CompletedProcess([], 0, b"whsec_fixture\n", b"")
            with patch("stripe_local.subprocess.run", return_value=response) as call:
                self.assertEqual(0, run("secret", root))
            self.assertEqual(["stripe", "listen", "--print-secret"], call.call_args.args[0])
            self.assertEqual("sk_test_fixture", call.call_args.kwargs["env"]["STRIPE_API_KEY"])
            self.assertIn("SHOP_PAYMENT_WEBHOOK_SECRET=whsec_fixture", path.read_text())

    def test_stripe_listener_uses_test_key_local_configured_port_and_no_live_flag(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / ".novabank/env/dev.env"
            path.parent.mkdir(parents=True)
            path.write_text("SHOP_PAYMENT_API_KEY=sk_test_fixture\nSHOP_APP_PORT=18081\nSHOP_PAYMENT_PROVIDER=stripe\n")
            with patch("stripe_local.subprocess.call", return_value=0) as call:
                self.assertEqual(0, run("listen", root))
            self.assertEqual(["stripe", "listen", "--forward-to", "http://localhost:18081/api/payments/stripe/webhook"], call.call_args.args[0])
            path.write_text("SHOP_PAYMENT_API_KEY=sk_live_fixture\n")
            with patch("stripe_local.subprocess.call") as call:
                with self.assertRaises(ValueError):
                    run("listen", root)
                call.assert_not_called()

    def test_stripe_login_next_step_is_allowlisted_and_never_shell(self):
        help_result = subprocess.CompletedProcess([], 0, b"--non-interactive", b"")
        login_result = subprocess.CompletedProcess([], 0, json.dumps({"browser_url": "https://dashboard.stripe.com/stripecli/confirm", "next_step": "stripe login --complete-device"}).encode(), b"")
        completed = subprocess.CompletedProcess([], 0, b"", b"")
        with patch("stripe_local.subprocess.run", side_effect=[help_result, login_result, completed]) as call:
            self.assertEqual(0, login())
        self.assertEqual(["stripe", "login", "--complete-device"], call.call_args.args[0])
        bad = subprocess.CompletedProcess([], 0, json.dumps({"browser_url": "https://dashboard.stripe.com/confirm", "next_step": "bash -c whoami"}).encode(), b"")
        with patch("stripe_local.subprocess.run", side_effect=[help_result, bad]):
            with self.assertRaises(ValueError):
                login()


if __name__ == "__main__":
    unittest.main()
