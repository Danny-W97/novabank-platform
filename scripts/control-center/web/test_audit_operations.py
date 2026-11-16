"""Audit tiles use closed Maven selections and synthetic Testcontainers, not operator connections."""
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

from server import ACTION_MAP, MAVEN_ARGS, Panel

AUDIT = ('audit-load-transfers', 'audit-security-http', 'audit-totp-restore-login',
         'audit-payment-bank', 'audit-payment-shop', 'audit-payment-transport')


class AuditOperationsTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.panel = Panel(Path(self.directory.name))
        self.addCleanup(self.panel.close)

    def test_profiles_confirmation_no_arbitrary_target_and_fixed_test_selection(self):
        for action in AUDIT:
            self.assertTrue(ACTION_MAP[action].changes)
            with self.assertRaises(ValueError): self.panel.submit(action, 'prod', True)
            with self.assertRaises(ValueError): self.panel.submit(action, 'dev', False)
            with self.assertRaises(ValueError): self.panel.submit(action, 'test', True, {'url': 'http://localhost:8080'})
            with patch('server.os.name', 'posix'):
                command = self.panel.command(action, 'test')
            self.assertTrue(command[1].endswith('scripts/ci/audit_extended_verify.py'));self.assertIn(command[2],('load','transport','security','totp','payment-bank','payment-shop'))
        self.assertNotIn('audit-load-transfers',MAVEN_ARGS)  # exported verifier, never the operator's active JAR

    def test_runner_does_not_inject_operator_test_secrets_or_stripe_keys(self):
        self.panel.jobs['fixture'] = {'action': 'audit-load-transfers', 'profile': 'test', 'status': 'running', 'log': ''}
        process = Mock(); process.stdout.readline.return_value = b''; process.wait.return_value = 0
        with patch.object(self.panel.configuration_files, 'test_environment', side_effect=AssertionError('Operator env must not be used')), \
                patch('server.subprocess.Popen', return_value=process) as spawn, \
                patch('server.java_executable', return_value=None), \
                patch.dict(os.environ, {'TEST_POSTGRES_PASSWORD': 'fixture-only', 'STRIPE_API_KEY': 'fixture-only'}):
            self.assertEqual(0, self.panel.run_command('fixture', ['synthetic-maven'], time.monotonic()+10, ()))
        env = spawn.call_args.kwargs['env']
        self.assertNotIn('STRIPE_API_KEY', env)
        self.assertNotIn('TEST_POSTGRES_PASSWORD', env)
        self.panel.jobs.clear()


if __name__ == '__main__': unittest.main()
