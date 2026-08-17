package org.demo.shopapp.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public class RefundRepository {

    private final JdbcTemplate jdbcTemplate;

    public RefundRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RefundRow> findByIdempotencyKey(String key) {
        return query(
                "SELECT id, order_id, order_number, idempotency_key, amount, currency, status, "
                        + "reason, bank_reference, failure_reason FROM shop_refunds WHERE idempotency_key = ?",
                key
        );
    }

    public Optional<RefundRow> findByIdempotencyKeyForUpdate(String key) {
        return query(
                "SELECT id, order_id, order_number, idempotency_key, amount, currency, status, "
                        + "reason, bank_reference, failure_reason FROM shop_refunds "
                        + "WHERE idempotency_key = ? FOR UPDATE",
                key
        );
    }

    public boolean existsCompletedForOrder(long orderId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM shop_refunds WHERE order_id = ? AND status = 'COMPLETED')",
                Boolean.class,
                orderId
        ));
    }

    public RefundRow create(
            long orderId,
            String orderNumber,
            String key,
            BigDecimal amount,
            String currency,
            String reason
    ) {
        jdbcTemplate.update(
                "INSERT INTO shop_refunds (order_id, order_number, idempotency_key, amount, currency, status, reason) "
                        + "VALUES (?, ?, ?, ?, ?, 'PENDING', ?)",
                orderId, orderNumber, key, amount, currency, reason
        );
        return findByIdempotencyKey(key).orElseThrow();
    }

    public RefundRow markCompleted(String key, String bankReference) {
        jdbcTemplate.update(
                "UPDATE shop_refunds SET status = 'COMPLETED', bank_reference = ?, failure_reason = NULL, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE idempotency_key = ?",
                bankReference, key
        );
        return findByIdempotencyKey(key).orElseThrow();
    }

    public RefundRow markFailed(String key, String failureReason) {
        jdbcTemplate.update(
                "UPDATE shop_refunds SET status = 'FAILED', failure_reason = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE idempotency_key = ? AND status <> 'COMPLETED'",
                limit(failureReason), key
        );
        return findByIdempotencyKey(key).orElseThrow();
    }

    public RefundRow markPending(String key, String failureReason) {
        jdbcTemplate.update(
                "UPDATE shop_refunds SET status = 'PENDING', failure_reason = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE idempotency_key = ? AND status = 'PENDING'",
                limit(failureReason), key
        );
        return findByIdempotencyKey(key).orElseThrow();
    }

    private Optional<RefundRow> query(String sql, Object... args) {
        var rows = jdbcTemplate.query(sql, (rs, rowNum) -> new RefundRow(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getString("order_number"),
                rs.getString("idempotency_key"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getString("bank_reference"),
                rs.getString("failure_reason")
        ), args);
        return rows.stream().findFirst();
    }

    private static String limit(String value) {
        if (value == null || value.isBlank()) {
            return "Bank odrzucił refundację.";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    public record RefundRow(
            Long id,
            Long orderId,
            String orderNumber,
            String idempotencyKey,
            BigDecimal amount,
            String currency,
            String status,
            String reason,
            String bankReference,
            String failureReason
    ) {
    }
}
