"""Temporary settings/backup roots only. No real backups, timers, env files or services."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from operator_settings import OperatorSettings, DEFAULTS, MARKER, backup_path, ensure_backup_owner
from dev_configuration import ConfigurationConflict
from legacy_history import LegacyHistory
from server import redact, Panel


class OperatorSettingsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / "project"
        self.root.mkdir()
        self.settings = OperatorSettings(self.root)

    def tearDown(self):
        self.temp.cleanup()

    def test_read_is_non_mutating_and_save_does_not_install_or_move_backups(self):
        state = self.settings.state("dev")
        self.assertFalse(self.settings.path.exists())
        updated = state["values"] | {"backupAt": "23:15", "drWeekday": "MON", "backupRetentionDays": 45}
        result = self.settings.save("dev", state["revision"], updated, True)
        self.assertEqual(updated, result["values"])
        self.assertTrue(self.settings.path.is_file())
        self.assertFalse((self.root / ".novabank/backups").exists())
        self.assertEqual(DEFAULTS["test"], self.settings.state("test")["values"])
        with self.assertRaises(ConfigurationConflict):
            self.settings.save("dev", state["revision"], updated, True)

    def test_invalid_profiles_fields_types_times_and_retention_are_rejected(self):
        state = self.settings.state("dev")
        for change in ({"backupAt": "24:00"}, {"drAt": "12:60"}, {"drWeekday": "Sun;rm"},
                       {"backupRetentionDays": 0}, {"artifactRetentionDays": True}, {"tempRetentionDays": 366},
                       {"backupDirectory": "/"}, {"backupDirectory": "../outside"}, {"backupDirectory": ".novabank/env"},
                       {"backupDirectory": ".novabank/backups/a;touch marker"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.settings.save("dev", state["revision"], state["values"] | change, True)
        for profile in ("prod", None, [], True):
            with self.assertRaises(ValueError):
                self.settings.state(profile)
        with self.assertRaises(ValueError):
            self.settings.save("dev", state["revision"], state["values"], "true")
        with self.assertRaises(ValueError):
            self.settings.save("dev", state["revision"], state["values"] | {"command": "whoami"}, True)
        self.assertFalse(self.settings.path.exists())

    def test_external_backup_directory_is_dedicated_empty_and_project_owned(self):
        target = Path(self.temp.name) / "novabank-dev-backups-fixture"
        self.assertEqual(target, ensure_backup_owner(self.root, str(target)))
        self.assertFalse(target.exists())
        ensure_backup_owner(self.root, str(target), create=True)
        self.assertTrue((target / MARKER).exists())
        (target / "fixture.dump").write_text("fixture")
        ensure_backup_owner(self.root, str(target), create=True)
        other = Path(self.temp.name) / "other-project"
        other.mkdir()
        with self.assertRaises(ValueError):
            ensure_backup_owner(other, str(target), create=True)
        self.assertEqual("fixture", (target / "fixture.dump").read_text())
        unowned = Path(self.temp.name) / "novabank-dev-backups-unowned"
        unowned.mkdir(); (unowned / "old.dump").write_text("keep")
        with self.assertRaises(ValueError):
            ensure_backup_owner(self.root, str(unowned), create=True)
        self.assertFalse((unowned / MARKER).exists())

    def test_symlinks_hardlinks_and_corrupt_settings_are_not_overwritten(self):
        target = self.root / "safe.json"
        target.write_text("{}")
        self.settings.path.parent.mkdir(parents=True)
        self.settings.path.symlink_to(target)
        with self.assertRaises(ValueError):
            self.settings.state("dev")
        self.settings.path.unlink()
        os.link(target, self.settings.path)
        with self.assertRaises(ValueError):
            self.settings.state("dev")
        self.settings.path.unlink()
        self.settings.path.write_text("not-json")
        with self.assertRaises(ValueError):
            self.settings.state("dev")
        self.assertEqual("not-json", self.settings.path.read_text())
        link = self.root / ".novabank/backups/link"
        link.parent.mkdir(parents=True); link.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ValueError):
            backup_path(self.root, ".novabank/backups/link/nested")

    def test_environment_snapshot_has_only_fixed_non_secret_fields(self):
        values = self.settings.state("dev")["values"] | {"backupRetentionDays": 90, "backupDirectory": ".novabank/backups/second"}
        env = self.settings.environment(values)
        self.assertEqual("90", env["NOVABANK_WEB_BACKUP_RETENTION_DAYS"])
        self.assertEqual(str(self.root / ".novabank/backups/second"), env["NOVABANK_WEB_BACKUP_DIRECTORY"])
        self.assertFalse(any("PASSWORD" in key or "TOKEN" in key for key in env))
        self.assertEqual({}, self.settings.execution("dev", "unit-all"))
        self.assertEqual({}, self.settings.environment({}))

    def test_loading_test_does_not_inspect_dev_backup_directory(self):
        state = self.settings.state("dev")
        self.settings.save("dev", state["revision"], state["values"], True)
        with patch("operator_settings.ensure_backup_owner", side_effect=AssertionError("No DEV drive probe")):
            self.assertEqual(DEFAULTS["test"], self.settings.state("test")["values"])


class CliHistoryTests(unittest.TestCase):
    def test_import_is_deduplicated_redacted_and_never_modifies_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            history = LegacyHistory(root, redact)
            history.source.parent.mkdir(parents=True)
            source = ("2026-10-16T08:00:00Z | OK | Build banku\n"
                      "2026-10-16T08:01:00Z | BLAD:1 | api_key=fixture-private-value user@example.invalid\n"
                      "2026-10-16T08:02:00Z | OK | <script>not executable</script>\n"
                      "not a CLI entry; run command\n")
            history.source.write_text(source)
            self.assertIn("nowych wpisów 3", history.import_now())
            self.assertIn("nowych wpisów 0", history.import_now())
            self.assertEqual(source, history.source.read_text())
            result = history.state()
            self.assertEqual(3, len(result["items"]))
            self.assertNotIn("fixture-private-value", json.dumps(result))
            self.assertNotIn("user@example.invalid", json.dumps(result))
            self.assertNotIn("fixture-private-value", history.path.read_text())
            self.assertIn("<script>", result["items"][0]["title"])

    def test_history_is_bounded_and_masked_again_on_read(self):
        with tempfile.TemporaryDirectory() as directory:
            history = LegacyHistory(Path(directory), lambda text: text.replace("private-fixture", "***"))
            history.source.parent.mkdir(parents=True)
            history.source.write_text("".join(f"2026-10-16T08:00:00Z | OK | Fixture {i} private-fixture\n" for i in range(600)))
            history.import_now()
            self.assertEqual(500, len(history.state()["items"]))
            self.assertNotIn("private-fixture", history.path.read_text())
            history.path.write_text("{}")
            with self.assertRaises(ValueError):
                history.state()

    def test_imported_history_does_not_become_replayable_jobs(self):
        with tempfile.TemporaryDirectory() as directory:
            panel = Panel(Path(directory))
            panel.cli_history.source.write_text("2026-10-16T08:00:00Z | OK | vault-unseal\n")
            panel.cli_history.import_now()
            self.assertFalse(panel.jobs)
            self.assertEqual(1, len(panel.cli_history.state()["items"]))
            for action in ("migrate-cli-backup", "migrate-cli-dr"):
                with self.assertRaises(ValueError):
                    panel.submit(action, "dev", True, {"command": "whoami"})
                panel.jobs["fixture"] = {"action": action, "status": "running"}
                with self.assertRaises(ValueError):
                    panel.cancel("fixture")
                panel.jobs.clear()
            panel.close()

    def test_multiple_imports_of_maximum_unicode_titles_remain_readable(self):
        with tempfile.TemporaryDirectory() as directory:
            history = LegacyHistory(Path(directory), lambda text: text)
            history.source.parent.mkdir(parents=True)
            for start in (0, 250):
                history.source.write_text("".join(f"2026-10-16T08:00:00Z | OK | {i} " + "\U0001f600" * 236 + "\n" for i in range(start, start + 250)), encoding="utf-8")
                history.import_now()
            self.assertEqual(500, len(history.state()["items"]))
