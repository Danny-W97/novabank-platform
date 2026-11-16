"""No real env files or running infrastructure; all secrets are fixtures."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from dev_env_layout import migrate
from dev_configuration import assignments, DevConfiguration, ConfigurationConflict
from env_ownership import normalize_pair, OwnershipConflict, owner, records


class OwnershipTests(unittest.TestCase):
    def test_owners(self):
        for key in ("POSTGRES_USER", "SHOP_POSTGRES_PORT", "REDIS_HOST"):
            self.assertEqual("project", owner(key))
        for key in ("POSTGRES_PASSWORD", "SHOP_POSTGRES_PASSWORD", "METRICS_PASSWORD", "BANK_API_KEY", "BANK_API_RATE_LIMIT_PER_MINUTE", "ADMIN_EMAIL"):
            self.assertEqual("dev", owner(key))
        self.assertIsNone(owner("VAULT_SECRET_ID"))

    def test_raw_values_comments_bom_and_effective_settings_preserved(self):
        before = {"project": b'\xef\xbb\xbf# comment\r\nPOSTGRES_USER=fixture\r\nPOSTGRES_PASSWORD=fixture-password\r\nBANK_API_KEY=fixture-key\r\nUNKNOWN=keep\r\n',
                  "dev": b"# dev comment\nBANK_API_KEY=fixture-key\nREDIS_PORT=16379\n"}
        after, report = normalize_pair(before)
        self.assertTrue(after["project"].startswith(b"\xef\xbb\xbf# comment\r\n"))
        self.assertIn(b'POSTGRES_PASSWORD=fixture-password', after["dev"])
        self.assertIn(b"UNKNOWN=keep", after["project"])
        self.assertNotIn(b"BANK_API_KEY", after["project"])
        self.assertNotIn(b"REDIS_PORT", after["dev"])
        self.assertEqual(assignments(before["project"]) | assignments(before["dev"]), assignments(after["project"]) | assignments(after["dev"]))
        self.assertEqual(after, normalize_pair(after)[0])
        self.assertNotIn("fixture-password", str(report))

    def test_conflicts_need_exact_authority_and_do_not_regenerate(self):
        before = {"project": b"BANK_API_KEY=old-fixture\nBANK_API_RATE_LIMIT_PER_MINUTE=99\n", "dev": b"BANK_API_KEY=current-fixture\nBANK_API_RATE_LIMIT_PER_MINUTE=11\n"}
        with self.assertRaises(OwnershipConflict):
            normalize_pair(before)
        with self.assertRaises(OwnershipConflict):
            normalize_pair(before, ["BANK_API_KEY"])
        after, report = normalize_pair(before, ["BANK_API_KEY", "BANK_API_RATE_LIMIT_PER_MINUTE"])
        self.assertEqual(before["dev"], after["dev"])
        self.assertEqual(set(), assignments(after["project"]).keys())
        self.assertEqual(2, len(report["resolvedUsingDev"]))
        with self.assertRaises(OwnershipConflict):
            normalize_pair(before, ["POSTGRES_PASSWORD"])

    def test_database_and_unknown_conflicts_always_stop(self):
        for key in ("POSTGRES_PASSWORD", "UNKNOWN"):
            with self.assertRaises(OwnershipConflict):
                normalize_pair({"project": f"{key}=old\n".encode(), "dev": f"{key}=new\n".encode()}, ["BANK_API_KEY", "BANK_API_RATE_LIMIT_PER_MINUTE"])
        with self.assertRaises(OwnershipConflict):
            normalize_pair({"project": b"UNKNOWN=identical\n", "dev": b"UNKNOWN=identical\n"})
        with self.assertRaises(OwnershipConflict):
            records(b"BANK_API_KEY=a\nBANK_API_KEY=a\n")
        with self.assertRaises(OwnershipConflict):
            records(b"touch /tmp/not-a-program\n")

    def test_moving_ambiguous_loader_literals_is_rejected_without_secret_in_error(self):
        for value in (b'"fixture-password"', b"'fixture-password'", b"fixture$variable", b"fixture#comment", b"fixture\\escape"):
            with self.subTest(format=value[:1]):
                with self.assertRaises(OwnershipConflict) as caught:
                    normalize_pair({"project": b"POSTGRES_PASSWORD=" + value + b"\n", "dev": b""})
                self.assertNotIn("fixture", str(caught.exception))

    def fixture(self, root):
        folder = root / ".novabank/env"
        folder.mkdir(parents=True)
        (folder / "project.env").write_bytes(b"# fixture\nPOSTGRES_USER=fixture\nPOSTGRES_PASSWORD=fixture-password\nBANK_API_KEY=old-fixture\n")
        (folder / "dev.env").write_bytes(b"BANK_API_KEY=current-fixture\n")
        return folder

    def test_mechanical_migration_is_backed_up_nonrotating_and_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            folder = self.fixture(root)
            original = {name: (folder / f"{name}.env").read_bytes() for name in ("project", "dev")}
            audit = migrate(root, False, ["BANK_API_KEY"])
            self.assertFalse(audit["applied"])
            self.assertFalse((folder / "backups").exists())
            report = migrate(root, True, ["BANK_API_KEY"])
            for name, raw in original.items():
                self.assertEqual(raw, (root / report["backup"] / f"{name}.env").read_bytes())
            self.assertEqual("current-fixture", assignments((folder / "dev.env").read_bytes())["BANK_API_KEY"])
            self.assertNotIn("POSTGRES_PASSWORD", assignments((folder / "project.env").read_bytes()))
            self.assertFalse(migrate(root, True)["applied"])

    def test_failed_second_write_rolls_back_and_retains_original_backup(self):
        import os
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            folder = self.fixture(root)
            config = DevConfiguration(root, "fixture")
            before = config.snapshot()
            replace = os.replace
            def failing(source, target):
                if Path(target).name == "dev.env":
                    raise OSError("fixture-only")
                return replace(source, target)
            with patch("dev_configuration.os.replace", side_effect=failing):
                with self.assertRaises(OSError):
                    migrate(root, True, ["BANK_API_KEY"])
            self.assertEqual(before, config.snapshot())
            backups = list((folder / "backups").glob("dedup-*"))
            self.assertEqual(1, len(backups))
            self.assertEqual(before["project"], (backups[0] / "project.env").read_bytes())

    def test_unapproved_conflict_creates_no_backup_and_no_write(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            folder = self.fixture(root)
            before = DevConfiguration(root, "fixture").snapshot()
            with self.assertRaises(OwnershipConflict):
                migrate(root, True)
            self.assertFalse((folder / "backups").exists())
            self.assertEqual(before, DevConfiguration(root, "fixture").snapshot())


if __name__ == "__main__":
    unittest.main()
