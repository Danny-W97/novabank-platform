"""Generated temporary artifacts only, not the real workspace."""
import os
from pathlib import Path
import tempfile
import time
import unittest
from maintenance_operations import cleanup


class MaintenanceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def make(self, name, days=40):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('fixture')
        when = time.time() - days * 86400
        os.utime(path, (when, when))
        return path

    def test_archive_keeps_secrets_source_active_logs_and_recent_files(self):
        report = self.make('target/surefire-reports/result.xml')
        temp = self.make('tmp/generated.tmp', 10)
        keep = [self.make('tmp/secret.tmp'), self.make('tmp/dev.env'), self.make('src/Source.txt'),
                self.make('.novabank/logs/bank-dev.log'), self.make('target/surefire-reports/recent.xml', 1)]
        result = cleanup(self.root, 'a' * 32)
        self.assertIn('Archiwizacja: 2', result)
        self.assertFalse(report.exists()); self.assertFalse(temp.exists())
        self.assertTrue(all(path.exists() for path in keep))
        self.assertTrue((self.root / '.novabank/control-center/web/artifacts-archive' / ('a' * 32) / 'tmp/generated.tmp').exists())

    def test_delete_is_bounded_and_does_not_remove_directory(self):
        report = self.make('target/failsafe-reports/result.xml')
        keep = self.make('.novabank/env/prod.bank.env')
        cleanup(self.root, 'b' * 32, delete=True)
        self.assertFalse(report.exists()); self.assertTrue(report.parent.exists()); self.assertTrue(keep.exists())

    def test_symlink_rejected_without_touching_target(self):
        keep = self.make('safe.txt')
        link = self.root / 'tmp'
        link.symlink_to(keep.parent, target_is_directory=True)
        with self.assertRaises(ValueError):
            cleanup(self.root, 'c' * 32, delete=True)
        self.assertTrue(keep.exists())

    def test_custom_retention_keeps_recent_files_and_rejects_invalid_values(self):
        keep = self.make('target/surefire-reports/keep.xml', 40)
        remove = self.make('tmp/old.tmp', 20)
        cleanup(self.root, 'd' * 32, retention_days=60, temp_days=15)
        self.assertTrue(keep.exists()); self.assertFalse(remove.exists())
        for days in (True, 0, 3651):
            with self.assertRaises(ValueError):
                cleanup(self.root, 'e' * 32, retention_days=days)


if __name__ == '__main__':
    unittest.main()
