import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
ACTION = ROOT / '.github/actions/archive-results/action.yml'


@unittest.skipIf(os.name == 'nt', 'Bash archive summary is tested in Linux/WSL')
class ArchiveResultsTests(unittest.TestCase):
    def test_only_archive_step_can_ignore_failure(self):
        action = ACTION.read_text(encoding='utf8')
        workflow = (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf8')
        self.assertEqual(1, action.count('continue-on-error: true'))
        self.assertIn('uses: actions/upload-artifact@v4', action)
        self.assertNotIn('continue-on-error:', workflow)
        self.assertNotIn('uses: actions/upload-artifact@', workflow)
        self.assertEqual(5, workflow.count('uses: ./.github/actions/archive-results'))
        self.assertIn('steps.archive.outcome', action)
        self.assertIn('test -s target/Bank-demo-0.0.1-SNAPSHOT.jar', workflow)
        self.assertIn('test -s shop-app/target/shop-app-0.0.1-SNAPSHOT.jar', workflow)

    def test_archive_failure_is_visible_without_rewriting_test_status(self):
        action = ACTION.read_text(encoding='utf8')
        script = '\n'.join(line[8:] for line in action.split('      run: |\n', 1)[1].splitlines()) + '\n'
        for outcome in ('success', 'failure', 'cancelled', 'skipped'):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as directory:
                summary = Path(directory) / 'summary.md'
                environment = dict(os.environ, GITHUB_STEP_SUMMARY=str(summary), ARCHIVE_NAME='fixture archive',
                                   ARCHIVE_OUTCOME=outcome, ARCHIVE_URL='https://example.invalid/artifact' if outcome == 'success' else '')
                result = subprocess.run(['bash', '-eu', '-c', script], env=environment, capture_output=True, text=True, timeout=5)
                self.assertEqual(0, result.returncode, result.stderr)
                report = summary.read_text(encoding='utf8')
                self.assertIn('fixture archive', report)
                if outcome == 'success':
                    self.assertIn('Uploaded:', report)
                    self.assertNotIn('::warning::', result.stdout)
                else:
                    self.assertIn('::warning::', result.stdout)
                    self.assertIn('Archive unavailable', report)
                    self.assertIn('results are unchanged', report)


if __name__ == '__main__':
    unittest.main()
