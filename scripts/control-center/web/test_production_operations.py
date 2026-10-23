"""Offline Vault/PROD fixtures only. Never calls the user's Vault or keytool."""
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

from production_operations import ADDRESS, FILES, ProductionOperations, ProductionFiles, VaultClient, VaultFailure, validate_parameters
from server import Panel, ACTION_MAP


class FakeVault:
    def __init__(self):
        self.calls = []
        self.fail_path = None
        self.root = False
        self.progress = 0

    def call(self, method, path, payload=None, token=""):
        self.calls.append((method, path, payload, token))
        if path == self.fail_path:
            raise VaultFailure(403)
        if path == "sys/seal-status":
            return {"sealed": False, "type": "shamir", "t": 3, "n": 5, "progress": self.progress}
        if path == "sys/unseal":
            self.progress += 1
            return {"sealed": self.progress < 3, "t": 3, "n": 5, "progress": self.progress}
        if path == "auth/token/lookup-self":
            return {"data": {"policies": ["root" if self.root else "operator"]}}
        if path == "sys/auth":
            return {"data": {"approle/": {"type": "approle"}}}
        if path.endswith("/role-id"):
            return {"data": {"role_id": "fixture-role-id"}}
        if path.endswith("/secret-id/lookup"):
            return {"data": {"secret_id_accessor": "fixture-old-accessor"}}
        if path.endswith("/secret-id"):
            return {"data": {"secret_id": "fixture-new-secret", "secret_id_accessor": "fixture-new-accessor"}}
        if path == "auth/approle/login":
            return {"auth": {"client_token": "fixture-client-token"}}
        return {}


class ProductionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.ops = ProductionOperations(self.root)
        self.fake = FakeVault()
        self.ops.client = lambda: self.fake
        self.logs = []

    def tearDown(self):
        self.temp.cleanup()

    def seed(self):
        path = self.ops.files.target("bank")
        path.parent.mkdir(parents=True)
        path.write_text(f"VAULT_ADDR={ADDRESS}\nVAULT_ROLE_ID=fixture-role-id\nVAULT_SECRET_ID=fixture-old-secret\nVAULT_SSL_TRUST_STORE_PASSWORD=fixture-truststore\n")

    def run_op(self, action, params=None):
        self.ops.run(action, params, self.logs.append)

    def test_unseal_uses_one_share_and_reports_actual_threshold(self):
        old = self.fake.call
        def sealed(method, path, payload=None, token=""):
            if path == "sys/seal-status":
                return {"sealed": self.fake.progress < 3, "type": "shamir", "t": 3, "n": 5, "progress": self.fake.progress}
            return old(method, path, payload, token)
        self.fake.call = sealed
        for share in ("fixture-share-one", "fixture-share-two", "fixture-share-three"):
            self.run_op("vault-unseal", {"unsealKey": share})
            self.assertNotIn(share, "".join(self.logs))
        self.assertIn("ODPIECZĘTOWANY", self.logs[-2])
        self.assertEqual(3, len([item for item in self.fake.calls if item[1] == "sys/unseal"]))
        self.assertEqual([], list(self.root.rglob("*")))

    def test_root_is_rejected_before_any_mutation(self):
        self.fake.root = True
        with self.assertRaisesRegex(ValueError, "root"):
            self.run_op("vault-setup", {"vaultToken": "fixture-operator"})
        self.assertFalse(any(method == "POST" for method, *_ in self.fake.calls))

    def test_setup_fixed_policies_are_read_only(self):
        self.run_op("vault-setup", {"vaultToken": "fixture-operator"})
        policies = [payload["policy"] for method, path, payload, _ in self.fake.calls if path.startswith("sys/policies/acl")]
        self.assertEqual(2, len(policies))
        self.assertTrue(all('capabilities = ["read"]' in policy and '"update"' not in policy for policy in policies))

    def test_rotate_then_finalize_preserves_old_until_user_confirmation(self):
        self.seed()
        self.run_op("vault-rotate-bank", {"vaultToken": "fixture-operator"})
        self.assertIn("fixture-new-secret", self.ops.files.target("bank").read_text())
        self.assertIn("fixture-old-accessor", self.ops.files.target("rotation").read_text())
        self.assertFalse(any(path.endswith("accessor/destroy") for _, path, *_ in self.fake.calls))
        self.assertNotIn("fixture-new-secret", "".join(self.logs))
        self.assertNotIn("fixture-client-token", "".join(self.logs))
        with self.assertRaises(ValueError):
            self.run_op("vault-finalize-bank", {"vaultToken": "fixture-operator", "restarted": False})
        self.run_op("vault-finalize-bank", {"vaultToken": "fixture-operator", "restarted": True})
        self.assertNotIn("BANK_PREVIOUS", self.ops.files.target("rotation").read_text())
        self.assertTrue(any(path.endswith("accessor/destroy") for _, path, *_ in self.fake.calls))

    def test_pending_rotation_blocks_next_generation(self):
        self.seed()
        self.run_op("vault-rotate-bank", {"vaultToken": "fixture-operator"})
        before = len(self.fake.calls)
        with self.assertRaisesRegex(ValueError, "niezakończona"):
            self.run_op("vault-rotate-all", {"vaultToken": "fixture-operator"})
        self.assertFalse(any(path.endswith("/secret-id") for _, path, *_ in self.fake.calls[before:]))

    def test_failed_commit_revokes_new_id_and_keeps_old_bootstrap(self):
        self.seed()
        before = self.ops.files.target("bank").read_bytes()
        with patch.object(self.ops.files, "commit", side_effect=OSError("fixture write failure")):
            with self.assertRaises(OSError):
                self.run_op("vault-rotate-bank", {"vaultToken": "fixture-operator"})
        self.assertEqual(before, self.ops.files.target("bank").read_bytes())
        self.assertTrue(any(path.endswith("accessor/destroy") and data["secret_id_accessor"] == "fixture-new-accessor" for _, path, data, _ in self.fake.calls))

    def test_absent_old_secret_empty_success_allows_rotation_but_403_does_not(self):
        self.seed()
        original_call = self.fake.call
        def empty_lookup(method, path, payload=None, token=""):
            return {} if path.endswith("/secret-id/lookup") else original_call(method, path, payload, token)
        self.fake.call = empty_lookup
        self.run_op("vault-rotate-bank", {"vaultToken": "fixture-operator"})
        self.assertIn("fixture-new-secret", self.ops.files.target("bank").read_text())
        self.assertFalse(self.ops.files.target("rotation").exists())
        self.fake.call = original_call
        self.fake.fail_path = "auth/approle/role/bank-prod/secret-id/lookup"
        before = self.ops.files.target("bank").read_bytes()
        with self.assertRaises(VaultFailure):
            self.run_op("vault-rotate-bank", {"vaultToken": "fixture-operator"})
        self.assertEqual(before, self.ops.files.target("bank").read_bytes())

    def test_finalization_403_does_not_discard_recovery_state(self):
        self.seed()
        self.run_op("vault-rotate-bank", {"vaultToken": "fixture-operator"})
        path = self.ops.files.target("rotation")
        before = path.read_bytes()
        self.fake.fail_path = "auth/approle/role/bank-prod/secret-id-accessor/lookup"
        with self.assertRaises(VaultFailure):
            self.run_op("vault-finalize-bank", {"vaultToken": "fixture-operator", "restarted": True})
        self.assertEqual(before, path.read_bytes())

    def test_bootstrap_preserves_existing_secret_and_rejects_other_vault(self):
        self.seed()
        self.run_op("prod-bootstrap", {})
        before = self.ops.files.target("bank").read_bytes()
        with self.assertRaises(ValueError):
            self.run_op("prod-bootstrap", {"bankSecretId": "fixture-other-secret"})
        self.assertEqual(before, self.ops.files.target("bank").read_bytes())

    def test_map_does_not_read_files(self):
        with patch.object(self.ops.files, "snapshot", side_effect=AssertionError("no reads")):
            self.run_op("prod-secret-map")
        self.assertIn("secret/data/Bank-demo/prod", "".join(self.logs))

    def test_validation_rejects_shell_bad_fields_and_missing_confirmation(self):
        for payload in ({"vaultToken": "secret\nvalue"}, {"vaultToken": "$(id)"}, {"vaultToken": "secret", "url": "https://remote"}):
            with self.assertRaises(ValueError):
                validate_parameters("vault-setup", payload)
        with self.assertRaises(ValueError):
            validate_parameters("vault-finalize-bank", {"vaultToken": "fixture"})
        with self.assertRaises(ValueError):
            validate_parameters("prod-tls-create", {"bankPassword": "short", "shopPassword": "short"})

    def test_guard_rejects_symlink_and_hardlink(self):
        self.seed()
        other = self.root / "other.env"
        other.write_text("fixture")
        path = self.ops.files.target("shop")
        path.hardlink_to(other)
        with self.assertRaises(ValueError):
            self.ops.files.snapshot()
        path.unlink()
        path.symlink_to(other)
        with self.assertRaises(ValueError):
            self.ops.files.snapshot()

    def test_tls_uses_env_and_preserves_existing_stores(self):
        def fake_keytool(command, **kwargs):
            self.assertNotIn("fixture-tls-password-bank", command)
            self.assertIn("-storepass:env", command)
            Path(command[command.index("-keystore") + 1]).write_bytes(b"fixture-p12-not-real")
            return Mock(returncode=0)
        with patch("production_operations.shutil.which", return_value="fake-keytool"), patch("production_operations.subprocess.run", side_effect=fake_keytool):
            self.run_op("prod-tls-create", {"bankPassword": "fixture-tls-password-bank", "shopPassword": "fixture-tls-password-shop"})
            with self.assertRaises(ValueError):
                self.run_op("prod-tls-create", {"bankPassword": "fixture-tls-password-bank", "shopPassword": "fixture-tls-password-shop"})

    def test_endpoint_guard_refuses_http_remote_methods(self):
        client = object.__new__(VaultClient)
        client.opener = Mock()
        for path in ("sys/seal", "sys/init", "sys/rekey", "secret/data/anything", "https://remote"):
            with self.assertRaises(ValueError):
                client.call("POST", path, {})
        client.opener.open.assert_not_called()

    def test_queue_does_not_persist_sensitive_parameters(self):
        panel = Panel(self.root)
        gate = threading.Event()
        token = "fixture-operator-private-only"
        def operation(*args):
            gate.wait(2)
        try:
            with patch.object(panel.production, "run", side_effect=operation):
                with self.assertRaises(ValueError):
                    panel.submit("vault-setup", "prod", True, {"vaultToken": token})
                result = panel.submit("vault-setup", "prod", True, {"vaultToken": token}, "PROD")
                self.assertNotIn(token, json.dumps(result))
                self.assertNotIn(token, json.dumps(panel.public_jobs()))
                with self.assertRaises(ValueError):
                    panel.cancel(result["id"])
                gate.set()
                panel.threads[-1].join(3)
            self.assertEqual({}, panel.private_parameters)
            saved = (panel.runtime / ("job-" + result["id"] + ".json")).read_text()
            self.assertNotIn(token, saved)
            self.assertIn("PROD", saved)
        finally:
            gate.set()
            panel.close()


    def test_failed_thread_start_does_not_keep_private_parameters_or_stuck_job(self):
        panel = Panel(self.root)
        try:
            with patch("server.threading.Thread.start", side_effect=RuntimeError("fixture cannot start")):
                with self.assertRaises(RuntimeError):
                    panel.submit("vault-setup", "prod", True, {"vaultToken": "fixture-operator-only"}, "PROD")
            self.assertEqual({}, panel.private_parameters)
            self.assertEqual({}, panel.jobs)
            self.assertEqual([], panel.threads)
        finally:
            panel.close()


if __name__ == "__main__":
    unittest.main()
