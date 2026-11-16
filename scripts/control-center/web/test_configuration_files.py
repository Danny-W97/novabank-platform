"""Temporary fixtures only; no real files, databases, Stripe or Docker."""
import base64
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from configuration_files import ConfigurationFiles, validate_entry
from dev_configuration import ConfigurationConflict, assignments


class FileEditorTests(unittest.TestCase):
    def test_stable_merchant_mapping_validation_is_shell_safe(self):
        validate_entry("SHOP_MERCHANT_ID", "novatech-shop", "dev")
        validate_entry("BANK_API_KEY_MERCHANTS", "legacy=novatech-shop,new-key=novatech-shop", "dev")
        validate_entry("BANK_PAYMENT_MERCHANTS", "novatech-shop=" + "2" * 26 + "+" + "3" * 26, "dev")
        for value in ("legacy=shop;whoami", "legacy=$(whoami)", "legacy=shop|other=shop"):
            with self.assertRaises(ValueError):
                validate_entry("BANK_API_KEY_MERCHANTS", value, "dev")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.files = ConfigurationFiles(self.root, "fixture-token")

    def tearDown(self):
        self.temp.cleanup()

    def write(self, file, text):
        path = self.files.target(file)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def save(self, file="dev", values=None, remove=None, risk=False):
        profile = "test" if file == "test" else "dev"
        state = self.files.state(profile)
        return self.files.save(profile, file, state["revision"], values or {}, remove or [], True, risk)

    def test_read_is_nonmutating_and_only_lists_profile_files(self):
        self.assertEqual(["project", "dev", "monitoring"], [f["id"] for f in self.files.state("dev")["files"]])
        self.assertEqual(["test"], [f["id"] for f in self.files.state("test")["files"]])
        self.assertFalse((self.root / ".novabank").exists())
        for profile in ("prod", "DEV", "../"):
            with self.assertRaises(ValueError):
                self.files.state(profile)

    def test_secrets_hidden_until_explicit_reveal_and_no_store_in_state(self):
        value = "fixture-private-password"
        self.write("project", "POSTGRES_PASSWORD=" + value + "\n")
        state = self.files.state("dev")
        self.assertNotIn(value, json.dumps(state))
        self.assertIsNone(state["files"][0]["entries"][0]["value"])
        with self.assertRaises(ValueError):
            self.files.reveal("dev", "project", "POSTGRES_PASSWORD", state["revision"], False)
        self.assertEqual(value, self.files.reveal("dev", "project", "POSTGRES_PASSWORD", state["revision"], True)["value"])

    def test_existing_password_can_change_only_with_risk_confirmation(self):
        self.write("dev", "# preserve comment\nPOSTGRES_PASSWORD=fixture-password-old\nOTHER=preserve\n")
        with self.assertRaises(ConfigurationConflict):
            self.save("dev", {"POSTGRES_PASSWORD": "fixture-password-new"})
        self.save("dev", {"POSTGRES_PASSWORD": "fixture-password-new"}, risk=True)
        raw = self.files.target("dev").read_text()
        self.assertIn("OTHER=preserve", raw)
        self.assertIn("# preserve comment", raw)
        self.assertIn("POSTGRES_PASSWORD=fixture-password-new", raw)

    def test_key_and_duplicate_update_remove_and_wrong_profile(self):
        self.write("project", "BANK_API_KEY=old-fixture\n")
        self.write("dev", "BANK_API_KEY=old-fixture\nSHOP_PAYMENT_PROVIDER=bank-demo\n")
        self.save(values={"BANK_API_KEY": "new-fixture"}, risk=True)
        self.assertNotIn("BANK_API_KEY", assignments(self.files.target("project").read_bytes()))
        self.assertEqual("new-fixture", assignments(self.files.target("dev").read_bytes())["BANK_API_KEY"])
        self.save(remove=["SHOP_PAYMENT_PROVIDER"])
        self.assertNotIn("SHOP_PAYMENT_PROVIDER", self.files.target("dev").read_text())
        with self.assertRaises(ValueError):
            self.files.save("test", "dev", self.files.state("test")["revision"], {}, [], True, True)

    def test_reveal_rejects_stale_revision(self):
        path = self.write("dev", "BANK_API_KEY=first\n")
        state = self.files.state("dev")
        path.write_text("BANK_API_KEY=second\n")
        with self.assertRaises(ConfigurationConflict):
            self.files.reveal("dev", "dev", "BANK_API_KEY", state["revision"], True)

    def test_paths_shell_production_and_live_keys_rejected(self):
        for key, value, file in (("BASH_ENV", "x", "dev"), ("PATH", "x", "project"), ("BANK_SSL_ENABLED", "true", "dev"),
                                 ("SPRING_PROFILES_ACTIVE", "prod", "dev"), ("BANK_API_KEY", "$(whoami)", "dev"),
                                 ("SHOP_PAYMENT_API_KEY", "sk_live_fixture", "dev"), ("TEST_REDIS_HOST", "remote.example", "test"),
                                 ("TEST_POSTGRES_URL", "jdbc:postgresql://localhost:5300/bankdb", "test")):
            with self.subTest(key=key):
                with self.assertRaises(ValueError):
                    validate_entry(key, value, file)
        with self.assertRaises(ValueError):
            self.files.target("../prod.bank")

    def test_valid_test_configuration_is_used_by_runner(self):
        self.save("test", {"TEST_BANK_API_KEY": "fixture-test-key", "TEST_POSTGRES_URL": "jdbc:postgresql://localhost:15432/bank_test"})
        self.assertEqual("fixture-test-key", self.files.test_environment()["TEST_BANK_API_KEY"])
        self.assertFalse(self.files.target("dev").exists())

    def test_port_updates_derived_url_and_duplicate_ports_are_rejected(self):
        self.write("dev", "BANK_APP_PORT=8080\nSHOP_APP_PORT=8081\nBANK_API_URL=http://localhost:8080\n")
        self.save(values={"BANK_APP_PORT": "18080"})
        values = assignments(self.files.target("dev").read_bytes())
        self.assertEqual("http://localhost:18080", values["BANK_API_URL"])
        with self.assertRaises(ValueError):
            self.save(values={"BANK_APP_PORT": "8081"})

    def test_metrics_updates_sync_existing_environment_files(self):
        self.write("dev", "METRICS_PASSWORD=fixture-old-password\n")
        self.write("project", "METRICS_PASSWORD=fixture-old-password\n")
        self.write("monitoring", "METRICS_PASSWORD=fixture-old-password\n")
        self.save("monitoring", {"METRICS_PASSWORD": "fixture-new-password"}, risk=True)
        for file in ("dev", "monitoring"):
            self.assertEqual("fixture-new-password", assignments(self.files.target(file).read_bytes())["METRICS_PASSWORD"])
        self.assertNotIn("METRICS_PASSWORD", assignments(self.files.target("project").read_bytes()))

    def test_owner_blocks_wrong_file_creation_and_wrong_copy_removal(self):
        self.write("project", "BANK_API_KEY=fixture-key\n")
        self.write("dev", "BANK_API_KEY=fixture-key\n")
        before = self.files.snapshot()
        for file, values, remove in (("project", {"BANK_API_KEY": "fixture-other"}, []),
                                     ("project", {}, ["BANK_API_KEY"]),
                                     ("dev", {"REDIS_PORT": "16379"}, [])):
            with self.assertRaises(ValueError):
                self.save(file, values, remove, risk=True)
        self.assertEqual(before, self.files.snapshot())

    def test_unapproved_legacy_conflict_blocks_even_unrelated_save(self):
        self.write("project", "BANK_API_KEY=fixture-old\n")
        self.write("dev", "BANK_API_KEY=fixture-current\n")
        before = self.files.snapshot()
        with self.assertRaises(ConfigurationConflict):
            self.save(values={"SHOP_APP_PORT": "18081"})
        self.assertEqual(before, self.files.snapshot())

    def test_totp_key_length_and_existing_rotation(self):
        with self.assertRaises(ValueError):
            self.save(values={"TOTP_ENCRYPTION_KEY": "bad"})
        old = base64.b64encode(b"a" * 32).decode()
        new = base64.b64encode(b"b" * 32).decode()
        self.write("dev", f"TOTP_ENCRYPTION_KEY={old}\n")
        with self.assertRaises(ConfigurationConflict):
            self.save(values={"TOTP_ENCRYPTION_KEY": new})
        self.save(values={"TOTP_ENCRYPTION_KEY": new}, risk=True)

    def test_second_replace_failure_rolls_back_both_files(self):
        self.write("project", "BANK_API_KEY=fixture-before\n")
        self.write("dev", "BANK_API_KEY=fixture-before\n")
        before = self.files.snapshot()
        replace = os.replace
        calls = 0
        def failing(source, target):
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError("fixture-only")
            return replace(source, target)
        with patch("configuration_files.os.replace", side_effect=failing):
            with self.assertRaises(OSError):
                self.save(values={"BANK_API_KEY": "fixture-after"}, risk=True)
        self.assertEqual(before, self.files.snapshot())
        self.assertEqual([], list((self.root / ".novabank/env").glob(".control-config-*")))

    def test_parallel_saves_accept_exactly_one_revision(self):
        import concurrent.futures
        state = self.files.state("test")
        def save(value):
            try:
                self.files.save("test", "test", state["revision"], {"TEST_BANK_API_KEY": value}, [], True, True)
                return True
            except ConfigurationConflict:
                return False
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            self.assertEqual(1, sum(pool.map(save, ["fixture-first", "fixture-second"])))

    def test_symlink_and_hardlink_rejected(self):
        path = self.write("dev", "BANK_API_KEY=fixture\n")
        other = self.root / "other.env"
        try:
            os.link(path, other)
        except OSError:
            self.skipTest("hardlinks unavailable")
        with self.assertRaises(ValueError):
            self.files.state("dev")


if __name__ == "__main__":
    unittest.main()
