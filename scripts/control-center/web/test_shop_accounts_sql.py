"""Opt-in real SQL in an owned disposable shop fixture, never in the user's DEV DB."""
import json
import os
from pathlib import Path
import subprocess
import time
import unittest
import uuid

from dev_shop_accounts import filters, listing_sql, detail_sql
from dev_accounts import read_query

DOCKER = ["docker", "--host", "unix:///var/run/docker.sock"]
ROOT = Path(__file__).resolve().parents[3]


@unittest.skipUnless(os.environ.get("NOVABANK_SHOP_ACCOUNTS_SQL_TEST") == "1", "Optional isolated shop account PostgreSQL test")
class ShopAccountPostgreSQLTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = "shop-accounts-it-" + uuid.uuid4().hex[:12]
        subprocess.run(DOCKER + ["run", "--pull=never", "--rm", "-d", "--network", "none", "--name", cls.container,
                                "--label", "novabank.control.test=shop-account-reader", "-e", "POSTGRES_HOST_AUTH_METHOD=trust", "postgres:18"], capture_output=True, check=True, timeout=30)
        cls.addClassCleanup(cls.cleanup_owned)
        for _ in range(80):
            if subprocess.run(DOCKER + ["exec", cls.container, "pg_isready", "-U", "postgres"], capture_output=True, timeout=5).returncode == 0:
                break
            time.sleep(.25)
        else:
            raise RuntimeError("Owned shop PostgreSQL fixture unavailable")
        migrations = ROOT / "shop-app/src/main/resources/db/migration"
        for name in ("V1__Initial_schema.sql", "V3__Add_shop_users.sql", "V4__Separate_shop_auth_from_bank_account.sql", "V8__add_shop_user_roles.sql", "V9__Add_shop_account_profile_and_address.sql"):
            cls.sql((migrations / name).read_text())
        cls.sql("""INSERT INTO shop_users(id,email,password_hash,customer_id,active,role,first_name,last_name,phone)
            SELECT CASE WHEN i=61 THEN 9223372036854775807 ELSE i END, 'fixture'||i||'@example.invalid',
            'fixture-password-hash', 'customer-'||i, i%2=1, CASE WHEN i%3=0 THEN 'ADMIN' ELSE 'CUSTOMER' END,
            CASE WHEN i=1 THEN NULL WHEN i=2 THEN 'Żaneta' ELSE 'Demo' END,
            CASE WHEN i=1 THEN NULL ELSE 'Fixture'||i END, CASE WHEN i=2 THEN 'fixture-private-phone' ELSE NULL END
            FROM generate_series(1,61) i;
            INSERT INTO shop_user_addresses(user_id,recipient_name,street,house_number,postal_code,city,country)
            VALUES (2,'fixture-private-recipient','fixture-private-street','1','fixture','Fixture','Fixture');""")

    @classmethod
    def sql(cls, sql, failure=False):
        result = subprocess.run(DOCKER + ["exec", "-i", cls.container, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "postgres"], input=sql, capture_output=True, text=True, timeout=15)
        if failure:
            return result.returncode != 0
        if result.returncode:
            raise AssertionError("Owned shop SQL fixture failed: " + result.stderr)
        return result.stdout.strip()

    @classmethod
    def cleanup_owned(cls):
        result = subprocess.run(DOCKER + ["inspect", cls.container, "--format", '{{index .Config.Labels "novabank.control.test"}}'], capture_output=True, text=True, timeout=10)
        if result.returncode == 0 and result.stdout.strip() == "shop-account-reader" and cls.container.startswith("shop-accounts-it-"):
            subprocess.run(DOCKER + ["rm", "-f", "-v", cls.container], capture_output=True, check=True, timeout=15)

    def rows(self, **values):
        return json.loads(self.sql(listing_sql(filters({"profile": ["dev"], **{key: [value] for key, value in values.items()}}))))

    def test_filter_search_and_pages_preserve_ids_and_null_names(self):
        page = self.rows()
        self.assertEqual(51, len(page))
        self.assertEqual("9223372036854775807", page[0]["id"])
        next_page = self.rows(before=page[49]["id"])
        self.assertEqual(61, len(page[:50] + next_page))
        self.assertEqual(61, len({row["id"] for row in page[:50] + next_page}))
        matches = self.rows(status="INACTIVE", role="ADMIN")
        self.assertTrue(matches)
        self.assertTrue(all(not row["active"] and row["role"] == "ADMIN" for row in matches))
        self.assertEqual("2", self.rows(q="żANETA")[0]["id"])
        self.assertIn("2", [row["id"] for row in self.rows(q="customer-2")])
        self.assertEqual("9223372036854775807", self.rows(q="9223372036854775807")[0]["id"])
        self.assertEqual("9223372036854775807", self.rows(q="fixture61@example.invalid")[0]["id"])
        self.assertEqual([], self.rows(q="%' OR true; --"))
        self.assertIsNone(self.rows(q="fixture1@example.invalid")[0]["firstName"])

    def test_detail_excludes_credentials_phone_address_and_payment_data(self):
        raw = self.sql(detail_sql("2"))
        detail = json.loads(raw)
        self.assertTrue(detail["hasPhone"])
        self.assertTrue(detail["hasAddress"])
        self.assertFalse(json.loads(self.sql(detail_sql("1")))["hasPhone"])
        self.assertFalse(json.loads(self.sql(detail_sql("1")))["hasAddress"])
        for value in ("fixture-password-hash", "fixture-private-phone", "fixture-private-street", "fixture-private-recipient", "bankAccountNumber"):
            self.assertNotIn(value, raw)
        self.assertEqual("", self.sql(detail_sql("9000")))

    def test_read_only_queries_cannot_mutate_users(self):
        before = self.sql("SELECT count(*), max(updated_at) FROM shop_users;")
        self.rows(q="Demo")
        self.sql(detail_sql("1"))
        self.assertTrue(self.sql(read_query("UPDATE shop_users SET active=false;"), failure=True))
        self.assertEqual(before, self.sql("SELECT count(*), max(updated_at) FROM shop_users;"))
