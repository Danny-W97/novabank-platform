package org.demo.bankdemo.maintenance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/** Inventory only. Counts are NOT evidence of an absence of an external payment. */
public final class PaymentMigrationReview {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public PaymentMigrationReview(DataSource source) {
        jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(10);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setReadOnly(true); transaction.setTimeout(15);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Review review() {
        return transaction.execute(status -> {
            // Enforced by PostgreSQL as well as the JDBC read-only hint.
            jdbc.execute("SET TRANSACTION READ ONLY");
            var counts = new LinkedHashMap<String,Long>();
            counts.put("unassignedAuthorizations", count("SELECT count(*) FROM payment_authorizations WHERE merchant_id IS NULL"));
            counts.put("unmappedOriginalPayments", count("SELECT count(*) FROM bank_transactions t WHERE t.type='PAYMENT' AND t.refund_original_id IS NULL AND NOT EXISTS (SELECT 1 FROM payment_purchase_attempts a WHERE a.bank_reference=t.reference_number)"));
            counts.put("conflictingPurchaseGroups", count("SELECT count(*) FROM (SELECT payment_order_id,destination_account_id FROM bank_transactions WHERE type='PAYMENT' AND refund_original_id IS NULL AND payment_order_id IS NOT NULL GROUP BY payment_order_id,destination_account_id HAVING count(*)>1) c"));
            counts.put("contradictoryAuthorizations", count("SELECT count(*) FROM payment_authorizations a LEFT JOIN bank_transactions t ON t.idempotency_key=a.payment_idempotency_key WHERE (a.status IN ('COMPLETED','REDEEMED') AND (t.id IS NULL OR a.bank_reference IS DISTINCT FROM t.reference_number)) OR (a.status IN ('FAILED','PENDING') AND (a.bank_reference IS NOT NULL OR t.id IS NOT NULL))"));
            var stop = counts.values().stream().anyMatch(value -> value > 0);
            return new Review(stop ? "STOP_MAPPING_REQUIRED" : "INVENTORY_ONLY", Map.copyOf(counts),
                    "No rows changed. Counts do not prove no in-flight payment. Reconcile the shop/provider snapshot, approve an explicit merchant/order/attempt/reference mapping and a restore test before any backfill.");
        });
    }
    private long count(String query) {return jdbc.queryForObject(query, Long.class);}
    public record Review(String status, Map<String,Long> counts, String nextAction) { }
}
