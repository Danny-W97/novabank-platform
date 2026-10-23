import contextlib
import io
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import security_checks as scanner


class SecurityChecksTests(unittest.TestCase):
    def test_linux_container_uses_runner_uid_gid_and_keeps_mounts_restricted(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            with patch.object(scanner.os, 'name', 'posix'), \
                    patch.object(scanner.os, 'getuid', return_value=1001, create=True), \
                    patch.object(scanner.os, 'getgid', return_value=1002, create=True), \
                    patch.object(scanner.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1)) as run:
                result = scanner.container(scanner.GITLEAKS, [(source, '/source', False), (source, '/reports', True)], ['dir'], network=False)
            args = run.call_args.args[0]
            self.assertEqual(1, result)
            self.assertEqual('1001:1002', args[args.index('--user') + 1])
            self.assertIn('--network=none', args)
            self.assertIn(f'{source.resolve()}:/source:ro', args)
            self.assertIn(f'{source.resolve()}:/reports:rw', args)
            self.assertIn('HOME=/tmp', args)
            self.assertIn('XDG_CACHE_HOME=/tmp/.cache', args)
            self.assertNotIn('--privileged', args)

    def test_windows_does_not_require_posix_identity_calls(self):
        with patch.object(scanner.os, 'name', 'nt'), \
                patch.object(scanner.os, 'getuid', create=True) as getuid, \
                patch.object(scanner.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as run:
            self.assertEqual(0, scanner.container(scanner.GITLEAKS, [], ['dir']))
        getuid.assert_not_called()
        self.assertNotIn('--user', run.call_args.args[0])

    def test_missing_malformed_or_invalid_report_cannot_pass_as_zero_findings(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'report.json'
            with self.assertRaises(ValueError):
                scanner.sanitize(report)
            for payload in ('not json', '{}', '[null]', '["not a finding"]'):
                report.write_text(payload, encoding='utf8')
                with self.assertRaises(ValueError):
                    scanner.sanitize(report)
            report.write_text('[]', encoding='utf8')
            self.assertEqual(0, scanner.sanitize(report))

    def test_report_sanitization_removes_values_and_raw_matches(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'report.json'
            finding = {'RuleID': 'fixture-rule', 'File': 'fixture.py', 'StartLine': 1,
                       'Commit': 'fixture-commit', 'Fingerprint': 'fixture-fingerprint',
                       'Secret': 'synthetic-' + 'sensitive-value', 'Match': 'raw-' + 'match'}
            report.write_text(json.dumps([finding]), encoding='utf8')
            self.assertEqual(1, scanner.sanitize(report))
            sanitized = json.loads(report.read_text(encoding='utf8'))[0]
            self.assertEqual({'RuleID', 'File', 'StartLine', 'Commit', 'Fingerprint'}, set(sanitized))
            self.assertNotIn(finding['Secret'], report.read_text(encoding='utf8'))
            self.assertNotIn(finding['Match'], report.read_text(encoding='utf8'))

    def test_dependency_errors_remain_blocking_and_have_a_summary(self):
        for exit_code in (0, 1, 2, 125):
            with self.subTest(exit_code=exit_code), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                summary = root / 'actions-summary.md'
                def export(destination):
                    destination.mkdir()
                    return []
                with patch.object(scanner, 'ROOT', root), patch.object(scanner, 'export', side_effect=export), \
                        patch.object(scanner, 'container', return_value=exit_code), \
                        patch.dict(os.environ, {'GITHUB_STEP_SUMMARY': str(summary)}), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(int(exit_code != 0), scanner.main('dependencies'))
                expected = 'PASS' if exit_code == 0 else 'FAIL'
                report = json.loads((root / 'target/security/dependencies-summary.json').read_text(encoding='utf8'))
                self.assertEqual(expected, report['verdict'])
                self.assertIn(expected, summary.read_text(encoding='utf8'))
                self.assertEqual(exit_code, report['checks'][0]['exitCode'])

    @unittest.skipUnless(os.name == 'posix' and os.environ.get('NOVABANK_SCANNER_OWNERSHIP_TEST') == '1', 'Opt-in real Linux Docker ownership test')
    def test_real_scanner_report_is_owned_and_sanitizable_by_runner(self):
        with tempfile.TemporaryDirectory(prefix='novabank-scanner-ownership-') as directory:
            root = Path(directory)
            source = root / 'source'; source.mkdir()
            reports = root / 'reports'; reports.mkdir()
            (source / 'fixture.py').write_text('api_key = "' + secrets.token_hex(32) + '"\n', encoding='utf8')
            code = scanner.container(scanner.GITLEAKS, [(source, '/source', False), (reports, '/reports', True)],
                                     ['dir', '--redact=100', '--no-banner', '--report-format=json', '--report-path=/reports/result.json', '/source'], network=False)
            self.assertEqual(1, code)
            report = reports / 'result.json'
            self.assertEqual(os.getuid(), report.stat().st_uid)
            self.assertGreater(scanner.sanitize(report), 0)


if __name__ == '__main__':
    unittest.main()
