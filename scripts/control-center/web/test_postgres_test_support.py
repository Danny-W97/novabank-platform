import subprocess
import unittest
from unittest.mock import patch

from postgres_test_support import wait_for_postgres


class PostgresFixtureReadinessTests(unittest.TestCase):
    def test_waits_for_final_tcp_server_not_initialization_socket(self):
        docker = ['docker', '--host', 'unix:///var/run/docker.sock']
        with patch('postgres_test_support.subprocess.run', side_effect=[
                subprocess.CompletedProcess([], 1), subprocess.CompletedProcess([], 0)]) as run, \
                patch('postgres_test_support.time.sleep') as sleep:
            wait_for_postgres(docker, 'owned-fixture')
        self.assertEqual(2, run.call_count)
        for call in run.call_args_list:
            self.assertEqual(docker + ['exec', 'owned-fixture', 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres'], call.args[0])
            self.assertEqual(5, call.kwargs['timeout'])
        sleep.assert_called_once_with(.2)

    def test_timeout_fails_instead_of_continuing_with_migrations(self):
        with patch('postgres_test_support.subprocess.run', return_value=subprocess.CompletedProcess([], 1)), \
                patch('postgres_test_support.time.monotonic', side_effect=[0, 31]), \
                patch('postgres_test_support.time.sleep') as sleep:
            with self.assertRaisesRegex(AssertionError, 'TCP readiness timeout'):
                wait_for_postgres(['docker'], 'owned-fixture', timeout=30)
        sleep.assert_not_called()


if __name__ == '__main__':
    unittest.main()
