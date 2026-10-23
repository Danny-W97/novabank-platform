"""Query regressions on SQLite plus opt-in, owned PostgreSQL (never DEV)."""
import os
from pathlib import Path
import sqlite3
import subprocess
import unittest
import uuid
from postgres_test_support import wait_for_postgres

QUERY = (Path(__file__).resolve().parents[1] / 'flyway-status.sql').read_text(encoding='utf8')
HISTORY = 'CREATE TABLE flyway_schema_history (installed_rank INTEGER PRIMARY KEY, version VARCHAR(50), success BOOLEAN NOT NULL);'
SCENARIOS = (
    ('empty', '', ('0', '0', 'brak', '0')),
    ('numeric_versions', "(1,'9',TRUE),(2,'14',TRUE),(3,'15',TRUE)", ('3', '3', '15', '0')),
    ('rank_not_version', "(1,'2',TRUE),(13,'14',TRUE)", ('2', '13', '14', '0')),
    ('failed_and_repeatable', "(1,'14',TRUE),(2,'15',TRUE),(3,'16',FALSE),(4,NULL,TRUE)", ('4', '4', '15', '1')),
    ('repeatable_only', '(1,NULL,TRUE)', ('1', '1', 'brak', '0')),
    ('dotted_version', "(1,'9',TRUE),(2,'15.1',TRUE)", ('2', '2', '15.1', '0')),
    ('out_of_order_installation', "(1,'15',TRUE),(2,'14',TRUE)", ('2', '2', '14', '0')),
)


class FlywayStatusQueryTests(unittest.TestCase):
    def test_exact_diagnostic_query_handles_history_edge_cases(self):
        for label, rows, expected in SCENARIOS:
            with self.subTest(label=label), sqlite3.connect(':memory:') as connection:
                connection.create_function('current_database', 0, lambda: 'synthetic')
                connection.execute(HISTORY)
                if rows:
                    connection.execute('INSERT INTO flyway_schema_history VALUES ' + rows)
                result = connection.execute(QUERY).fetchone()
                self.assertEqual(('synthetic', *expected), tuple(map(str, result)))


@unittest.skipUnless(os.environ.get('NOVABANK_FLYWAY_SQL_TEST') == '1', 'Explicit disposable PostgreSQL fixture required')
class FlywayStatusPostgresTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.docker = ['docker', '--host', 'unix:///var/run/docker.sock']
        cls.container = 'flyway-status-it-' + uuid.uuid4().hex
        cls.addClassCleanup(cls.cleanup)
        subprocess.run(cls.docker + ['run', '-d', '--name', cls.container, '--network', 'none',
                       '--label', 'novabank.control.test=flyway-status', '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', 'postgres:18'],
                       check=True, capture_output=True, timeout=30)
        wait_for_postgres(cls.docker, cls.container)

    @classmethod
    def cleanup(cls):
        result = subprocess.run(cls.docker + ['inspect', cls.container, '--format', '{{index .Config.Labels "novabank.control.test"}}'], capture_output=True, text=True, timeout=10)
        if result.returncode == 0 and result.stdout.strip() == 'flyway-status' and cls.container.startswith('flyway-status-it-'):
            subprocess.run(cls.docker + ['rm', '-f', '-v', cls.container], check=True, capture_output=True, timeout=20)

    def test_exact_query_on_postgresql(self):
        for label, rows, expected in SCENARIOS:
            with self.subTest(label=label):
                sql = 'BEGIN; ' + HISTORY
                if rows:
                    sql += ' INSERT INTO flyway_schema_history VALUES ' + rows + ';'
                sql += QUERY + ' ROLLBACK;'
                result = subprocess.run(self.docker + ['exec', '-i', self.container, 'psql', '-h', '127.0.0.1', '-X', '-qAt', '-F', '\t', '-U', 'postgres', '-v', 'ON_ERROR_STOP=1'],
                                        input=sql, text=True, capture_output=True, timeout=15)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(('postgres', *expected), tuple(result.stdout.strip().split('\t')))


if __name__ == '__main__':
    unittest.main()
