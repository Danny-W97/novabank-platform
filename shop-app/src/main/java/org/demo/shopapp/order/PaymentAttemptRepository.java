package org.demo.shopapp.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PaymentAttemptRepository {
    private final JdbcTemplate jdbc;
    public PaymentAttemptRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<PaymentAttempt> byKey(String key) { return find("idempotency_key", key); }
    public Optional<PaymentAttempt> byAuthorization(String id) { return find("bank_authorization_id", id); }
    public Optional<PaymentAttempt> byExternal(String id) { return find("external_payment_id", id); }
    public Optional<PaymentAttempt> byId(String id) { return find("id", id); }
    private Optional<PaymentAttempt> find(String field, String value) {
        // field is a private constant from the methods above, never user input.
        return jdbc.query("SELECT * FROM shop_payment_attempts WHERE " + field + " = ?",
                PaymentAttemptRepository::row, value).stream().findFirst();
    }
    public Optional<PaymentAttempt> active(long order) {
        return jdbc.query("""
                SELECT * FROM shop_payment_attempts WHERE order_id = ?
                AND state IN ('CREATED','PROCESSING','AWAITING_AUTHORIZATION','UNKNOWN','SUCCEEDED')
                """, PaymentAttemptRepository::row, order).stream().findFirst();
    }
    public String merchantOfOrder(long order) {
        return jdbc.queryForObject("SELECT merchant_id FROM shop_orders WHERE id = ?", String.class, order);
    }
    public PaymentAttempt insert(long order, String merchant, String key, String provider,
                                 BigDecimal amount, String currency) {
        var id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO shop_payment_attempts
                (id,order_id,merchant_id,provider,idempotency_key,state,correlation_state,charged_amount,charged_currency)
                VALUES (?,?,?,?,?,'PROCESSING',?,?,?)
                """, id, order, merchant, provider, key, UUID.randomUUID().toString(), amount, currency);
        return byId(id).orElseThrow();
    }
    public void attachAuthorization(PaymentAttempt attempt, String authorization, String url) {
        requireOne(jdbc.update("""
                UPDATE shop_payment_attempts SET bank_authorization_id=?,authorization_url=?,
                state='AWAITING_AUTHORIZATION',updated_at=CURRENT_TIMESTAMP,version=version+1
                WHERE id=? AND state IN ('PROCESSING','UNKNOWN','AWAITING_AUTHORIZATION')
                AND (bank_authorization_id IS NULL OR bank_authorization_id=?)
                """, authorization, url, attempt.id(), authorization));
    }
    public void attachExternal(PaymentAttempt attempt, String external, String url) {
        requireOne(jdbc.update("""
                UPDATE shop_payment_attempts SET external_payment_id=?,checkout_url=?,updated_at=CURRENT_TIMESTAMP,
                state=CASE WHEN state='SUCCEEDED' THEN state ELSE 'AWAITING_AUTHORIZATION' END,version=version+1
                WHERE id=? AND state IN ('PROCESSING','UNKNOWN','AWAITING_AUTHORIZATION','SUCCEEDED')
                AND (external_payment_id IS NULL OR external_payment_id=?)
                """, external, url, attempt.id(), external));
    }
    public void succeed(PaymentAttempt attempt, String reference, String account) {
        requireOne(jdbc.update("""
                UPDATE shop_payment_attempts SET state='SUCCEEDED',financial_reference=?,customer_account_number=?,
                last_error=NULL,reconciliation_failures=0,reconciliation_suspended_at=NULL,
                updated_at=CURRENT_TIMESTAMP,version=version+1
                WHERE id=? AND (state IN ('PROCESSING','AWAITING_AUTHORIZATION','UNKNOWN')
                OR (state='SUCCEEDED' AND financial_reference=?))
                """, reference, account, attempt.id(), reference));
    }
    public void decline(PaymentAttempt attempt, String reason) {
        requireOne(jdbc.update("""
                UPDATE shop_payment_attempts SET state='DECLINED',last_error=?,reconciliation_failures=0,
                reconciliation_suspended_at=NULL,updated_at=CURRENT_TIMESTAMP,version=version+1
                WHERE id=? AND state IN ('PROCESSING','AWAITING_AUTHORIZATION','UNKNOWN','DECLINED')
                """, reason, attempt.id()));
    }
    public void unknown(PaymentAttempt attempt, String reason) {
        jdbc.update("""
                UPDATE shop_payment_attempts SET state='UNKNOWN',last_error=?,updated_at=CURRENT_TIMESTAMP,version=version+1
                WHERE id=? AND state IN ('PROCESSING','AWAITING_AUTHORIZATION','UNKNOWN')
                """, reason, attempt.id());
    }
    public void checked(PaymentAttempt attempt) {
        jdbc.update("""
                UPDATE shop_payment_attempts SET checked_at=CURRENT_TIMESTAMP,reconciliation_failures=0,last_error=NULL
                WHERE id=? AND reconciliation_suspended_at IS NULL
                """, attempt.id());
    }
    public int failure(PaymentAttempt attempt) {
        var counts = jdbc.query("""
                UPDATE shop_payment_attempts SET checked_at=CURRENT_TIMESTAMP,
                reconciliation_failures=LEAST(reconciliation_failures+1,3),
                reconciliation_suspended_at=CASE WHEN reconciliation_failures+1>=3 THEN CURRENT_TIMESTAMP ELSE NULL END,
                last_error='Payment reconciliation failed; outcome remains unresolved.'
                WHERE id=? AND reconciliation_suspended_at IS NULL
                RETURNING reconciliation_failures
                """, (rs,n)->rs.getInt(1), attempt.id());
        return counts.isEmpty() ? 0 : counts.getFirst();
    }
    public List<PaymentAttempt> candidates(Instant stale, int limit) {
        return jdbc.query("""
                SELECT a.* FROM shop_payment_attempts a JOIN shop_orders o ON o.id=a.order_id
                WHERE (a.state IN ('PROCESSING','AWAITING_AUTHORIZATION','UNKNOWN')
                  OR (a.state='SUCCEEDED' AND o.cart_cleanup_completed_at IS NULL AND o.historical_imported_at IS NULL))
                AND a.reconciliation_suspended_at IS NULL AND COALESCE(a.checked_at,a.created_at) < ?
                ORDER BY COALESCE(a.checked_at,a.created_at),a.id LIMIT ?
                """, PaymentAttemptRepository::row, java.sql.Timestamp.from(stale), Math.max(1,Math.min(limit,500)));
    }
    public OrderRepository.OrderRow orderForAttempt(PaymentAttempt attempt, OrderRepository orders) {
        var customer = jdbc.queryForObject("SELECT customer_id FROM shop_orders WHERE id=?",String.class,attempt.orderId());
        return orders.findByIdForUpdate(attempt.orderId(),customer);
    }
    public static void requireOne(int count) {
        if (count != 1) throw new IllegalStateException("Payment attempt state conflict; reconciliation required.");
    }
    private static PaymentAttempt row(ResultSet rs, int n) throws SQLException {
        var checked=rs.getTimestamp("checked_at");
        return new PaymentAttempt(rs.getString("id"),rs.getLong("order_id"),rs.getString("merchant_id"),
                rs.getString("provider"),rs.getString("idempotency_key"),rs.getString("state"),rs.getString("correlation_state"),
                rs.getBigDecimal("charged_amount"),rs.getString("charged_currency"),rs.getString("bank_authorization_id"),
                rs.getString("authorization_url"),rs.getString("external_payment_id"),rs.getString("checkout_url"),
                rs.getString("financial_reference"),rs.getString("customer_account_number"),
                rs.getTimestamp("created_at").toInstant(),checked==null?null:checked.toInstant(),rs.getLong("version"));
    }
}
