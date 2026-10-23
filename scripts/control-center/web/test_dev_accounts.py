"""No live database access: validation, immutable SQL templates and private-response guards."""
import json
import unittest
from unittest.mock import Mock

from dev_accounts import DevAccounts, filters, account_id, listing_sql, detail_sql


class DevAccountTests(unittest.TestCase):
    def test_profiles_unknown_duplicate_and_invalid_filters_rejected(self):
        for query in ({}, {"profile": ["test"]}, {"profile": ["prod"]}, {"profile": ["dev", "dev"]},
                      {"profile": ["dev"], "sql": ["SELECT 1"]}, {"profile": ["dev"], "q": ["a", "b"]},
                      {"profile": ["dev"], "q": ["a" * 129]}, {"profile": ["dev"], "q": ["\na"]},
                      {"profile": ["dev"], "status": ["active"]}, {"profile": ["dev"], "currency": ["P'L"]},
                      {"profile": ["dev"], "before": ["0"]}):
            with self.subTest(query=query), self.assertRaises(ValueError):
                filters(query)
        with self.assertRaises(ValueError):
            filters({"profile": ["dev"], "q": ["a"]}, detail=True)

    def test_ids_are_lossless_text_and_bounded_int64(self):
        for value in ("1", "9007199254740993", "9223372036854775807"):
            self.assertEqual(value, account_id(value))
        for value in (0, True, None, "01", "0", "-1", "1e2", "9223372036854775808", "1;DROP TABLE users"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                account_id(value)

    def test_sql_is_read_only_bounded_and_search_is_encoded_data(self):
        term = "Żaneta' OR true; -- % _ \\"
        query = listing_sql(filters({"profile": ["dev"], "q": [term], "status": ["BLOCKED"], "currency": ["PLN"], "before": ["9007199254740993"]}))
        self.assertNotIn(term, query)
        self.assertIn(term.encode().hex(), query)
        for value in ("BEGIN READ ONLY", "statement_timeout = '5s'", "lock_timeout = '1s'", "LIMIT 51", "ORDER BY a.id DESC", "a.id < 9007199254740993", "a.status = 'BLOCKED'", "a.currency = 'PLN'", "strpos("):
            self.assertIn(value, query)
        for sql in (query, detail_sql("1")):
            for forbidden in ("FOR UPDATE", "INSERT ", "UPDATE ", "DELETE ", "password_hash", "transaction_pin_hash", "totp_secret", "recovery", "phone", "birth_date"):
                self.assertNotIn(forbidden, sql)
        self.assertIn("RIGHT(a.account_number, 4)", query)
        self.assertNotIn('AS "accountNumber"', query)
        self.assertIn('AS "accountNumber"', detail_sql("1"))

    def test_listing_pages_and_cursor_do_not_round_ids(self):
        database = Mock()
        rows = [{"id": str(9223372036854775807 - n)} for n in range(51)]
        database._run.return_value = json.dumps(rows)
        result = DevAccounts(database).listing(filters({"profile": ["dev"]}))
        self.assertEqual(50, len(result["accounts"]))
        self.assertEqual(rows[49]["id"], result["next"])
        self.assertTrue(result["readOnly"])
        database._base_command.assert_called_once_with()
        database._run.assert_called_once()
        database._run.return_value = "[]"
        self.assertIsNone(DevAccounts(database).listing(filters({"profile": ["dev"]}))["next"])

    def test_missing_detail_and_database_failures_never_expose_errors(self):
        database = Mock()
        database._run.return_value = ""
        with self.assertRaises(LookupError):
            DevAccounts(database).detail("1")
        for failure in (RuntimeError("fixture-private-password"), ValueError("fixture-private-password")):
            database._base_command.side_effect = failure
            with self.assertRaises(RuntimeError) as caught:
                DevAccounts(database).detail("1")
            self.assertNotIn("fixture-private-password", str(caught.exception))
        database._base_command.side_effect = None
        for raw in ("not-json", "{}", "null"):
            database._run.return_value = raw
            with self.assertRaises(RuntimeError):
                DevAccounts(database).listing(filters({"profile": ["dev"]}))
