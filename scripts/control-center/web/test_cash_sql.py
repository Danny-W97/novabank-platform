"""Optional PostgreSQL tests: own ephemeral container, no DEV database access.

WSL: NOVABANK_CASH_SQL_TEST=1 python3 -B -m unittest discover -s scripts/control-center/web -p test_cash_sql.py -v
"""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import subprocess
import time
import unittest
import uuid

from dev_cash import adjustment_sql

ROOT = Path(__file__).resolve().parents[3]
DOCKER = ["docker", "--host", "unix:///var/run/docker.sock"]


@unittest.skipUnless(os.environ.get("NOVABANK_CASH_SQL_TEST") == "1", "Optional isolated Docker/PostgreSQL test")
class PostgreSQLTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = "bank-control-cash-it-" + uuid.uuid4().hex[:12]
        subprocess.run(DOCKER + ["run", "--rm", "-d", "--name", cls.container,
                                "--label", "novabank.control.test=cash", "-e", "POSTGRES_HOST_AUTH_METHOD=trust", "postgres:18"],
                       capture_output=True, check=True, timeout=30)
        cls.addClassCleanup(cls.remove_owned_container)
        for _ in range(80):
            result = subprocess.run(DOCKER + ["exec", cls.container, "pg_isready", "-U", "postgres"], capture_output=True, timeout=5)
            if result.returncode == 0:
                break
            time.sleep(.25)
        else:
            raise RuntimeError("Test PostgreSQL did not become ready")
        cls.sql("CREATE TABLE accounts (id BIGINT PRIMARY KEY, balance NUMERIC(19,2) NOT NULL CHECK (balance >= 0), "
                "currency CHAR(3), status VARCHAR(16), version BIGINT DEFAULT 0, updated_at TIMESTAMPTZ);")
        cls.sql((ROOT / "src/main/resources/db/migration/V25__dev_cash_adjustment_audit.sql").read_text())
        migrations = ROOT / "shop-app/src/main/resources/db/migration"
        for file in sorted(migrations.glob("V*.sql"), key=lambda path: int(path.name.split("__")[0][1:])):
            cls.sql(file.read_text())

    @classmethod
    def remove_owned_container(cls):
        label = subprocess.run(DOCKER + ["inspect", cls.container, "--format", '{{index .Config.Labels "novabank.control.test"}}'], capture_output=True, text=True, timeout=10)
        if label.returncode == 0 and label.stdout.strip() == "cash" and cls.container.startswith("bank-control-cash-it-"):
            subprocess.run(DOCKER + ["rm", "-f", "-v", cls.container], capture_output=True, check=True, timeout=15)

    @classmethod
    def sql(cls, query):
        result = subprocess.run(DOCKER + ["exec", "-i", cls.container, "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-U", "postgres"],
                                input=query, capture_output=True, text=True, timeout=20)
        if result.returncode:
            raise AssertionError(result.stderr)
        return result.stdout.strip()

    def setUp(self):
        self.sql("TRUNCATE dev_cash_adjustments, accounts; INSERT INTO accounts (id,balance,currency,status) VALUES (1,100,'PLN','ACTIVE');")

    def request(self, amount="25.00"):
        return {"accountId": 1, "amount": amount, "requestId": str(uuid.uuid4())}

    def test_credit_replay_and_payload_conflict_are_atomic(self):
        request = self.request()
        first = json.loads(self.sql(adjustment_sql("CREDIT", request)))
        second = json.loads(self.sql(adjustment_sql("CREDIT", request)))
        conflict = json.loads(self.sql(adjustment_sql("DEBIT", request)))
        self.assertFalse(first["replayed"])
        self.assertTrue(second["replayed"])
        self.assertEqual("IDEMPOTENCY_CONFLICT", conflict["error"])
        self.assertEqual("125.00|1", self.sql("SELECT balance,version FROM accounts WHERE id=1"))
        self.assertEqual("1", self.sql("SELECT count(*) FROM dev_cash_adjustments"))

    def test_parallel_same_id_executes_once(self):
        request = self.request()
        with ThreadPoolExecutor(max_workers=2) as workers:
            results = list(workers.map(lambda _: json.loads(self.sql(adjustment_sql("CREDIT", request))), range(2)))
        self.assertTrue(all(item["ok"] for item in results))
        self.assertEqual(1, sum(item["replayed"] for item in results))
        self.assertEqual("125.00|1", self.sql("SELECT balance,version FROM accounts WHERE id=1"))

    def test_parallel_debits_never_overdraw(self):
        requests = [self.request("80.00"), self.request("80.00")]
        with ThreadPoolExecutor(max_workers=2) as workers:
            results = list(workers.map(lambda request: json.loads(self.sql(adjustment_sql("DEBIT", request))), requests))
        self.assertEqual(1, sum(item["ok"] for item in results))
        self.assertEqual("20.00|1", self.sql("SELECT balance,version FROM accounts WHERE id=1"))

    def test_invalid_or_blocked_account_not_changed(self):
        self.sql("UPDATE accounts SET status='BLOCKED' WHERE id=1")
        self.assertFalse(json.loads(self.sql(adjustment_sql("CREDIT", self.request())))["ok"])
        self.assertEqual("100.00|0", self.sql("SELECT balance,version FROM accounts WHERE id=1"))

    def test_scheduler_failure_sql_persists_and_excludes_suspended_orders(self):
        source = (ROOT / "shop-app/src/main/java/org/demo/shopapp/order/OrderRepository.java").read_text()
        failure_method = source.split("public int recordReconciliationFailure", 1)[1].split("public boolean", 1)[0]
        failure_sql = re.search(r'"""(.*?)"""', failure_method, re.S).group(1)
        self.sql("INSERT INTO shop_orders(order_number,customer_id,merchant_account_number,total,currency,status,payment_idempotency_key) "
                 "VALUES ('fixture-order','fixture-customer','00000000000000000000000000',10,'PLN','PENDING','card-fixture');")
        order_id = self.sql("SELECT id FROM shop_orders WHERE order_number='fixture-order'")
        failure_sql = failure_sql.replace("?", "'Fixture HTTP 404'", 1).replace("?", order_id, 1)
        for expected in ("1", "2", "3", ""):
            self.assertEqual(expected, self.sql(failure_sql))
        self.assertEqual("PENDING|3|t|t", self.sql("SELECT status,reconciliation_failures,reconciliation_suspended_at IS NOT NULL," 
                                               "payment_reconciled_at IS NOT NULL FROM shop_orders WHERE id=" + order_id))
        candidate_method = source.split("public List<OrderRow> findReconciliationCandidates", 1)[1].split("public OrderRow", 1)[0]
        candidates_sql = re.search(r'"""(.*?)"""', candidate_method, re.S).group(1)
        candidates_sql = candidates_sql.replace("?", "CURRENT_TIMESTAMP + INTERVAL '1 day'", 1).replace("?", "50", 1)
        self.assertEqual("", self.sql(candidates_sql))
        self.sql("UPDATE shop_orders SET reconciliation_suspended_at=NULL,reconciliation_failures=2 WHERE id=" + order_id)
        # Actual repository reset statement, reconstructed from its Java string literals.
        reset_method = source.split("public void markPaymentReconciled", 1)[1].split("public int", 1)[0]
        reset_sql = "".join(re.findall(r'"([^"]*)"', reset_method)).replace("?", order_id)
        self.sql(reset_sql)
        self.assertEqual("0|t", self.sql("SELECT reconciliation_failures,reconciliation_last_error IS NULL FROM shop_orders WHERE id=" + order_id))


if __name__ == "__main__":
    unittest.main()
