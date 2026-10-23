"""DEV setup tests on temporary files only. Never touches real env or Docker."""
import base64
import concurrent.futures
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from dev_configuration import ConfigurationConflict, DevConfiguration, assignments


class DevConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.config = DevConfiguration(self.root, "fixture-token")

    def tearDown(self):
        self.temp.cleanup()

    def save(self, values=None):
        return self.config.save(self.config.state()["revision"], values or {}, True)

    def read(self, name):
        return assignments((self.root / f".novabank/env/{name}.env").read_bytes())

    def fixture(self):
        self.config.directory.mkdir(parents=True)
        (self.config.directory / "project.env").write_text("# Preserve comment\nPOSTGRES_DB=fixturedb\nPOSTGRES_USER=fixtureuser\nPOSTGRES_PASSWORD=old-fixture-password\nSHOP_POSTGRES_PASSWORD=shop-fixture-password\nOTHER_SETTING=keep-this\n", encoding="utf-8")
        key = base64.b64encode(b"x" * 32).decode()
        (self.config.directory / "dev.env").write_text(f"BANK_API_KEY=old-fixture-api-key\nTOTP_ENCRYPTION_KEY={key}\nSHOP_MERCHANT_ACCOUNT_NUMBER={'1' * 26}\nSHOP_PAYMENT_WEBHOOK_SECRET=old-fixture-webhook\n", encoding="utf-8")

    def test_read_does_not_create_configuration(self):
        state = self.config.state()
        self.assertFalse(self.config.directory.exists())
        self.assertFalse(state["ready"])
        self.assertTrue(all(not file["exists"] for file in state["files"]))
        self.assertEqual(64, len(state["revision"]))

    def test_first_save_creates_required_files_and_stable_secrets(self):
        result = self.save({"POSTGRES_DB": "customdb", "BANK_APP_PORT": "18080", "SHOP_APP_PORT": "18081"})
        self.assertTrue(result["ready"])
        self.assertFalse(result["paymentsReady"])
        project, dev = self.read("project"), self.read("dev")
        self.assertEqual("customdb", project["POSTGRES_DB"])
        self.assertEqual("dev", dev["SPRING_PROFILES_ACTIVE"])
        self.assertNotIn("POSTGRES_PASSWORD", project)
        self.assertEqual(64, len(dev["POSTGRES_PASSWORD"]))
        self.assertEqual(64, len(dev["BANK_API_KEY"]))
        self.assertEqual(32, len(base64.b64decode(dev["TOTP_ENCRYPTION_KEY"])))
        self.assertEqual("http://localhost:18080", dev["BANK_API_URL"])
        self.assertEqual("http://localhost:18081/payment/callback", dev["SHOP_PAYMENT_CALLBACK_URL"])
        self.save()
        self.assertEqual(dev["POSTGRES_PASSWORD"], self.read("dev")["POSTGRES_PASSWORD"])
        self.assertFalse(self.read("project").keys() & self.read("dev").keys())
        self.assertEqual(dev["TOTP_ENCRYPTION_KEY"], self.read("dev")["TOTP_ENCRYPTION_KEY"])
        self.assertEqual(dev["BANK_API_KEY"], self.read("dev")["BANK_API_KEY"])
        if os.name != "nt":
            for name in ("project", "dev"):
                self.assertEqual(0o600, (self.config.directory / f"{name}.env").stat().st_mode & 0o777)

    def test_public_state_never_exposes_secrets_or_merchant_number(self):
        self.fixture()
        state = self.config.state()
        encoded = json.dumps(state)
        for secret in ("old-fixture-password", "old-fixture-api-key", "old-fixture-webhook", "1" * 26, base64.b64encode(b"x" * 32).decode()):
            self.assertNotIn(secret, encoded)
        for field in state["fields"]:
            if field["kind"] in ("secret", "account", "managed"):
                self.assertIsNone(field["value"])

    def test_existing_settings_secrets_comments_and_account_preserved(self):
        self.fixture()
        result = self.save({"BANK_APP_PORT": "18080", "SHOP_MERCHANT_ACCOUNT_NUMBER": "", "POSTGRES_PASSWORD": ""})
        self.assertTrue(result["ready"])
        project, dev = self.read("project"), self.read("dev")
        self.assertNotIn("POSTGRES_PASSWORD", project)
        self.assertEqual("old-fixture-password", dev["POSTGRES_PASSWORD"])
        self.assertEqual("old-fixture-api-key", dev["BANK_API_KEY"])
        self.assertEqual("1" * 26, dev["SHOP_MERCHANT_ACCOUNT_NUMBER"])
        self.assertEqual("old-fixture-webhook", dev["SHOP_PAYMENT_WEBHOOK_SECRET"])
        self.assertEqual("keep-this", project["OTHER_SETTING"])
        self.assertIn("# Preserve comment", (self.config.directory / "project.env").read_text())

    def test_inherited_project_only_keys_move_to_dev_without_rotation(self):
        self.config.directory.mkdir(parents=True)
        key = base64.b64encode(b"x" * 32).decode()
        (self.config.directory / "project.env").write_text(f"BANK_API_KEY=project-fixture-key\nTOTP_ENCRYPTION_KEY={key}\nPOSTGRES_PASSWORD=old-fixture-password\n", encoding="utf-8")
        result = self.save()
        self.assertTrue(result["ready"])
        self.assertNotIn("BANK_API_KEY", self.read("project"))
        self.assertEqual("project-fixture-key", self.read("dev")["BANK_API_KEY"])
        self.assertEqual(key, self.read("dev")["TOTP_ENCRYPTION_KEY"])
        self.assertNotIn("TOTP_ENCRYPTION_KEY", self.read("project"))

    def test_protected_keys_and_database_identity_cannot_change(self):
        self.fixture()
        original = self.config.snapshot()
        for key, value in (("POSTGRES_DB", "otherdb"), ("POSTGRES_USER", "otheruser"), ("POSTGRES_PASSWORD", "different-fixture-password")):
            with self.assertRaises(ConfigurationConflict):
                self.save({key: value})
        for key in ("BANK_API_KEY", "TOTP_ENCRYPTION_KEY"):
            with self.assertRaises(ValueError):
                self.save({key: "replacement-fixture"})
        self.assertEqual(original, self.config.snapshot())

    def test_stale_revision_prevents_overwriting_manual_edits(self):
        self.save()
        old = self.config.state()["revision"]
        path = self.config.directory / "dev.env"
        with path.open("a", encoding="utf-8") as file:
            file.write("MANUAL_SETTING=preserve\n")
        with self.assertRaises(ConfigurationConflict):
            self.config.save(old, {"BANK_APP_PORT": "18080"}, True)
        self.assertEqual("preserve", self.read("dev")["MANUAL_SETTING"])

    def test_validation_no_arbitrary_fields_remote_hosts_shell_or_prod(self):
        bad = ({"VAULT_SECRET_ID": "no"}, {"POSTGRES_HOST": "remote.example"}, {"REDIS_HOST": "::1"},
               {"POSTGRES_PASSWORD": "fixture$(whoami)"}, {"POSTGRES_DB": "db\nSPRING_PROFILES_ACTIVE=prod"},
               {"BANK_APP_PORT": "65536"}, {"BANK_APP_PORT": "80"}, {"SHOP_APP_PORT": "8080"},
               {"BANK_APP_PORT": 8080}, {"SHOP_MERCHANT_ACCOUNT_NUMBER": "not-a-number"},
               {"POSTGRES_PASSWORD": "short"}, {"POSTGRES_DB": "a-b"})
        for values in bad:
            with self.subTest(keys=list(values)):
                with self.assertRaises(ValueError):
                    self.save(values)
        self.assertFalse(self.config.directory.exists())
        for confirmed in (False, "true", 1, None):
            with self.assertRaises(ValueError):
                self.config.save(self.config.state()["revision"], {}, confirmed)

    def test_conflicting_database_credentials_block_silent_synchronization(self):
        self.fixture()
        with (self.config.directory / "dev.env").open("a", encoding="utf-8") as file:
            file.write("POSTGRES_PASSWORD=other-fixture-password\n")
        original = self.config.snapshot()
        self.assertTrue(self.config.state()["warnings"])
        with self.assertRaises(ConfigurationConflict):
            self.save()
        self.assertEqual(original, self.config.snapshot())

    def test_invalid_existing_totp_key_not_regenerated(self):
        self.save()
        path = self.config.directory / "dev.env"
        path.write_bytes(path.read_bytes().replace(self.read("dev")["TOTP_ENCRYPTION_KEY"].encode(), b"invalid-existing-key"))
        result = self.save()
        self.assertFalse(result["ready"])
        self.assertTrue(result["warnings"])
        self.assertEqual("invalid-existing-key", self.read("dev")["TOTP_ENCRYPTION_KEY"])

    def test_invalid_stored_host_is_blank_in_form_and_can_be_corrected(self):
        self.save()
        path = self.config.directory / "project.env"
        path.write_bytes(path.read_bytes().replace(b"POSTGRES_HOST=localhost", b"POSTGRES_HOST=remote.example", 1))
        state = self.config.state()
        field = next(field for field in state["fields"] if field["key"] == "POSTGRES_HOST")
        self.assertEqual("", field["value"])
        self.assertFalse(state["ready"])
        result = self.save({"POSTGRES_HOST": "localhost"})
        self.assertTrue(result["ready"])
        self.assertEqual("localhost", self.read("project")["POSTGRES_HOST"])

    def test_failure_of_second_replace_restores_both_originals(self):
        self.fixture()
        original = self.config.snapshot()
        replace = os.replace
        def failing(source, target):
            if Path(target).name == "dev.env":
                raise OSError("fixture failure")
            return replace(source, target)
        with patch("dev_configuration.os.replace", side_effect=failing):
            with self.assertRaises(OSError):
                self.save({"BANK_APP_PORT": "18080"})
        self.assertEqual(original, self.config.snapshot())
        self.assertEqual([], list(self.config.directory.glob(".control-config-*")))

    def test_new_files_are_removed_after_failed_first_setup(self):
        replace = os.replace
        def failing(source, target):
            if Path(target).name == "dev.env":
                raise OSError("fixture failure")
            return replace(source, target)
        with patch("dev_configuration.os.replace", side_effect=failing):
            with self.assertRaises(OSError):
                self.save()
        self.assertEqual({"project": None, "dev": None}, self.config.snapshot())

    def test_parallel_save_uses_revision_and_one_wins(self):
        revision = self.config.state()["revision"]
        def submit(port):
            try:
                self.config.save(revision, {"BANK_APP_PORT": port}, True)
                return "saved"
            except ConfigurationConflict:
                return "conflict"
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as workers:
            results = list(workers.map(submit, ("18080", "19080")))
        self.assertCountEqual(["saved", "conflict"], results)

    def test_symlink_and_hardlink_targets_rejected(self):
        self.config.directory.mkdir(parents=True)
        source = self.root / "fixture.env"
        source.write_text("KEEP=fixture\n", encoding="utf-8")
        target = self.config.directory / "project.env"
        try:
            target.symlink_to(source)
        except OSError:
            self.skipTest("OS does not allow symlinks")
        with self.assertRaises(ValueError):
            self.config.state()
        target.unlink()
        os.link(source, target)
        with self.assertRaises(ValueError):
            self.config.state()
        self.assertEqual("KEEP=fixture\n", source.read_text())


if __name__ == "__main__":
    unittest.main()
