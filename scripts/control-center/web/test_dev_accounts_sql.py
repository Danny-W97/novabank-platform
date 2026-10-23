"""Opt-in real SQL QA, only in a labeled disposable container, without published ports."""
import json
import os
from pathlib import Path
import subprocess
import time
import unittest
import uuid

from dev_accounts import listing_sql, detail_sql, filters, read_query

DOCKER = ["docker", "--host", "unix:///var/run/docker.sock"]
ROOT = Path(__file__).resolve().parents[3]


@unittest.skipUnless(os.environ.get("NOVABANK_ACCOUNTS_SQL_TEST") == "1", "Optional isolated account PostgreSQL test")
class AccountPostgreSQLTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = "bank-accounts-it-" + uuid.uuid4().hex[:12]
        subprocess.run(DOCKER + ["run", "--pull=never", "--rm", "-d", "--network", "none", "--name", cls.container,
                                "--label", "novabank.control.test=account-reader", "-e", "POSTGRES_HOST_AUTH_METHOD=trust", "postgres:18"], capture_output=True, check=True, timeout=30)
        cls.addClassCleanup(cls.cleanup_owned)
        for _ in range(80):
            if subprocess.run(DOCKER + ["exec", cls.container, "pg_isready", "-U", "postgres"], capture_output=True, timeout=5).returncode == 0:
                break
            time.sleep(.25)
        else:
            raise RuntimeError("Owned PostgreSQL fixture unavailable")
        migrations = ROOT / "src/main/resources/db/migration"
        for name in ("V1__create_bank_schema.sql", "V8__add_totp_mfa.sql", "V10__add_email_verification_password_reset_and_login_history.sql"):
            cls.sql((migrations / name).read_text())
        cls.sql("""INSERT INTO users(id,email,password_hash,first_name,last_name,role,status,created_at,updated_at,totp_secret_encrypted)
            SELECT i, 'fixture'||i||'@example.invalid', 'fixture-password-hash', CASE WHEN i=1 THEN 'Żaneta' ELSE 'Demo' END,
            'Fixture'||i, 'USER', CASE WHEN i%3=0 THEN 'BLOCKED' ELSE 'ACTIVE' END, now(), now(), 'fixture-totp-secret'
            FROM generate_series(1,62) i;
            INSERT INTO accounts(id,account_number,owner_id,balance,currency,status,created_at,updated_at)
            SELECT CASE WHEN i=62 THEN 9223372036854775807 ELSE i END, lpad(i::text,26,'0'), i, 12345678901234567.89,
            CASE WHEN i%2=0 THEN 'EUR' ELSE 'PLN' END,
            CASE WHEN i%3=0 THEN 'CLOSED' WHEN i%3=1 THEN 'ACTIVE' ELSE 'BLOCKED' END,now(),now() FROM generate_series(1,62) i;""")

    @classmethod
    def sql(cls, sql, failure=False):
        result = subprocess.run(DOCKER + ["exec", "-i", cls.container, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "postgres"], input=sql, capture_output=True, text=True, timeout=15)
        if failure:
            return result.returncode != 0
        if result.returncode:
            raise AssertionError("Owned SQL fixture failed: " + result.stderr)
        return result.stdout.strip()

    @classmethod
    def cleanup_owned(cls):
        result = subprocess.run(DOCKER + ["inspect", cls.container, "--format", '{{index .Config.Labels "novabank.control.test"}}'], capture_output=True, text=True, timeout=10)
        if result.returncode == 0 and result.stdout.strip() == "account-reader" and cls.container.startswith("bank-accounts-it-"):
            subprocess.run(DOCKER + ["rm", "-f", "-v", cls.container], capture_output=True, check=True, timeout=15)

    def rows(self, **values):
        return json.loads(self.sql(listing_sql(filters({"profile": ["dev"], **{key: [value] for key, value in values.items()}}))))

    def test_filters_literal_search_masking_and_keyset_pages(self):
        page = self.rows()
        self.assertEqual(51, len(page))
        self.assertEqual("9223372036854775807", page[0]["id"])
        next_page = self.rows(before=page[49]["id"])
        self.assertEqual(62, len(page[:50] + next_page))
        self.assertEqual(62, len({row["id"] for row in page[:50] + next_page}))
        self.assertTrue(all(row["status"] == "BLOCKED" and row["currency"] == "EUR" for row in self.rows(status="BLOCKED", currency="EUR")))
        self.assertEqual("1", self.rows(q="żANETA")[0]["id"])
        self.assertEqual("9223372036854775807", self.rows(q="fixture62@example.invalid")[0]["id"])
        self.assertEqual("9223372036854775807", self.rows(q="PL00 0000 0000 0000 0000 0000 0062")[0]["id"])
        self.assertEqual([], self.rows(q="%' OR true; --"))
        self.assertNotIn("accountNumber", page[0])
        self.assertEqual("•••• 0062", page[0]["number"])

    def test_detail_fields_keep_decimal_precision_and_exclude_credentials(self):
        raw = self.sql(detail_sql("9223372036854775807"))
        detail = json.loads(raw)
        self.assertEqual("12345678901234567.89", detail["balance"])
        self.assertEqual("9223372036854775807", detail["id"])
        self.assertEqual("00000000000000000000000062", detail["accountNumber"])
        self.assertIsNone(detail["lastLoginAt"])
        self.assertNotIn("fixture-password-hash", raw)
        self.assertNotIn("fixture-totp-secret", raw)
        self.assertEqual("", self.sql(detail_sql("9000")))

    def test_read_transaction_rejects_writes_and_preserves_rows(self):
        before = self.sql("SELECT sum(balance)::text, max(updated_at) FROM accounts;")
        self.rows(q="Demo")
        self.sql(detail_sql("1"))
        self.assertTrue(self.sql(read_query("UPDATE accounts SET balance=0;"), failure=True))
        self.assertEqual(before, self.sql("SELECT sum(balance)::text, max(updated_at) FROM accounts;"))
