"""No live accounts: closed query templates, pagination and local shop DB identity guards."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from dev_shop_accounts import DevShopAccounts, DevShopDatabase, filters, listing_sql, detail_sql
from dev_cash import normalized_path


class ShopAccountTests(unittest.TestCase):
    def test_profiles_parameters_and_filters_are_strict(self):
        for query in ({}, {"profile": ["test"]}, {"profile": ["prod"]}, {"profile": ["dev", "dev"]},
                      {"profile": ["dev"], "q": ["a", "b"]}, {"profile": ["dev"], "q": ["a" * 129]},
                      {"profile": ["dev"], "q": ["\na"]}, {"profile": ["dev"], "role": ["USER"]},
                      {"profile": ["dev"], "status": ["BLOCKED"]}, {"profile": ["dev"], "sql": ["anything"]},
                      {"profile": ["dev"], "before": ["9223372036854775808"]}):
            with self.subTest(query=query), self.assertRaises(ValueError):
                filters(query)
        with self.assertRaises(ValueError):
            filters({"profile": ["dev"], "q": ["x"]}, detail=True)
        self.assertEqual("Żaneta", filters({"profile": ["dev"], "q": [" Żaneta "]})["q"])

    def test_fixed_read_only_sql_and_no_authentication_or_address_content(self):
        term = "Żaneta' OR true; -- % _ \\"
        query = listing_sql(filters({"profile": ["dev"], "q": [term], "status": ["INACTIVE"], "role": ["ADMIN"], "before": ["9007199254740993"]}))
        self.assertNotIn(term, query)
        for value in (term.encode().hex(), "u.active = false", "u.role = 'ADMIN'", "u.id < 9007199254740993", "LIMIT 51", "ORDER BY u.id DESC", "strpos(", "concat_ws("):
            self.assertIn(value, query)
        for sql in (query, detail_sql("1")):
            for required in ("BEGIN READ ONLY", "statement_timeout = '5s'", "lock_timeout = '1s'", "COMMIT"):
                self.assertIn(required, sql)
            for forbidden in ("FOR UPDATE", "INSERT ", "UPDATE ", "DELETE ", "password_hash", "token", "bank_account_number", "street", "recipient_name", "shop_orders"):
                self.assertNotIn(forbidden, sql)
        self.assertNotIn("u.phone", query)
        self.assertIn('AS "hasPhone"', detail_sql("1"))
        self.assertIn('AS "hasAddress"', detail_sql("1"))

    def test_lossless_page_cursor_and_missing_detail(self):
        database = Mock()
        rows = [{"id": str(9223372036854775807 - n)} for n in range(51)]
        database._run.return_value = json.dumps(rows)
        reader = DevShopAccounts(database)
        result = reader.listing(filters({"profile": ["dev"]}))
        self.assertEqual(50, len(result["accounts"]))
        self.assertEqual(rows[49]["id"], result["next"])
        self.assertTrue(result["readOnly"])
        database._run.return_value = "[]"
        self.assertIsNone(reader.listing(filters({"profile": ["dev"]}))["next"])
        database._run.return_value = ""
        with self.assertRaises(LookupError):
            reader.detail("1")

    def test_failures_hide_db_errors_and_private_values(self):
        database = Mock()
        database._base_command.side_effect = RuntimeError("fixture-private-password")
        with self.assertRaises(RuntimeError) as caught:
            DevShopAccounts(database).detail("1")
        self.assertNotIn("fixture-private-password", str(caught.exception))
        database._base_command.side_effect = None
        for raw in ("not-json", "null", "{}"):
            database._run.return_value = raw
            with self.assertRaises(RuntimeError):
                DevShopAccounts(database).listing(filters({"profile": ["dev"]}))

    def test_shop_database_is_fixed_to_matching_local_compose_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            database = DevShopDatabase(Path(directory), {}, {})
            labels = {"com.docker.compose.project": "bank-demo", "com.docker.compose.service": "shop-postgres",
                      "com.docker.compose.project.config_files": normalized_path(database.root / "docker-compose.yaml"),
                      "com.docker.compose.project.working_dir": normalized_path(database.root)}
            with patch("dev_shop_accounts.shutil.which", return_value="/usr/bin/docker"), patch("dev_shop_accounts.os.name", "posix"), patch.object(database, "_run") as run:
                run.return_value = json.dumps({"running": True, "labels": labels})
                command = database._base_command()
                self.assertEqual(["/usr/bin/docker", "--host", "unix:///var/run/docker.sock", "exec", "-i", "shop-postgres"], command[:6])
                self.assertEqual(["-U", "shopuser", "-d", "shopdb"], command[-4:])
                for key, value in (("com.docker.compose.service", "postgres"), ("com.docker.compose.project", "prod"),
                                   ("com.docker.compose.project.working_dir", "/another-checkout"), ("com.docker.compose.project.config_files", "/another-compose.yaml")):
                    run.return_value = json.dumps({"running": True, "labels": labels | {key: value}})
                    with self.subTest(key=key), self.assertRaises(RuntimeError):
                        database._base_command()
                run.return_value = json.dumps({"running": False, "labels": labels})
                with self.assertRaises(RuntimeError):
                    database._base_command()
                database.settings = {"SHOP_POSTGRES_HOST": "remote.example"}
                run.reset_mock()
                with self.assertRaises(RuntimeError):
                    database._base_command()
                run.assert_not_called()
                database.settings = {"SHOP_POSTGRES_USER": "shopuser;DROP TABLE shop_users"}
                run.return_value = json.dumps({"running": True, "labels": labels})
                with self.assertRaises(ValueError):
                    database._base_command()
