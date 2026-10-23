import tempfile
from pathlib import Path
import unittest
from server import ACTION_MAP,Panel


class K4ActionTests(unittest.TestCase):
    def test_k4_is_explicit_isolated_dev_test_operation(self):
        action=ACTION_MAP['audit-security-k4']
        self.assertEqual(('dev','test'),action.profiles)
        self.assertTrue(action.changes)
        with tempfile.TemporaryDirectory() as directory:
            panel=Panel(Path(directory))
            try:
                command=panel.command(action.id,'test')
                self.assertEqual('k4',command[-1])
                self.assertTrue(command[-2].endswith('scripts/ci/audit_extended_verify.py'))
                with self.assertRaises(ValueError):panel.command(action.id,'prod')
                with self.assertRaises(ValueError):panel.submit(action.id,'dev',False)
            finally:panel.close()


if __name__=='__main__':unittest.main()
