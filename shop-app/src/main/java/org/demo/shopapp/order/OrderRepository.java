package org.demo.shopapp.order;

import org.demo.shopapp.cart.CartItemResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class OrderRepository {

    private final JdbcTemplate jdbcTemplate;
    private final org.demo.shopapp.config.ShopOrderProperties properties;

    public OrderRepository(JdbcTemplate jdbcTemplate, org.demo.shopapp.config.ShopOrderProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public OrderRow create(
            String customerId,
            String merchantAccountNumber,
            BigDecimal total,
            String currency,
            List<CartItemResponse> items
    ) {
        var orderNumber = UUID.randomUUID().toString();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                    """
                    INSERT INTO shop_orders (
                        order_number, customer_id, customer_account_number,
                        merchant_account_number, total, currency, status, merchant_id
                    ) VALUES (?, ?, NULL, ?, ?, ?, 'PENDING', ?)
                    """,
                    new String[]{"id"}
            );
            statement.setString(1, orderNumber);
            statement.setString(2, customerId);
            statement.setString(3, merchantAccountNumber);
            statement.setBigDecimal(4, total);
            statement.setString(5, currency);
            statement.setString(6, properties.requireMerchantId());
            return statement;
        }, keyHolder);
        var orderId = keyHolder.getKey().longValue();
        for (CartItemResponse item : items) {
            jdbcTemplate.update(
                    """
                    INSERT INTO shop_order_items (
                        order_id, product_id, sku, product_name,
                        unit_price, quantity, line_total
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    orderId,
                    item.productId(),
                    item.sku(),
                    item.name(),
                    item.unitPrice(),
                    item.quantity(),
                    item.lineTotal()
            );
        }
        return findById(orderId, customerId);
    }

    public OrderRow findById(long orderId, String customerId) {
        return find(
                """
                SELECT id, order_number, customer_id, customer_account_number,
                       merchant_account_number, total, currency, status,
                       payment_idempotency_key, bank_authorization_id, payment_state,
                       bank_reference, failure_reason, payment_provider,
                       external_payment_id, external_checkout_url
                FROM shop_orders
                WHERE id = ? AND customer_id = ?
                """,
                orderId, customerId
        );
    }

    public OrderRow findByIdForUpdate(long orderId, String customerId) {
        return find(
                """
                SELECT id, order_number, customer_id, customer_account_number,
                       merchant_account_number, total, currency, status,
                       payment_idempotency_key, bank_authorization_id, payment_state,
                       bank_reference, failure_reason, payment_provider,
                       external_payment_id, external_checkout_url
                FROM shop_orders
                WHERE id = ? AND customer_id = ?
                FOR UPDATE
                """,
                orderId, customerId
        );
    }

    public List<OrderRow> findAll(String customerId) {
        return jdbcTemplate.query(
                        """
                        SELECT id
                        FROM shop_orders
                        WHERE customer_id = ?
                        ORDER BY created_at DESC
                        """,
                        (rs, rowNum) -> rs.getLong(1),
                        customerId
                ).stream()
                .map(id -> findById(id, customerId))
                .toList();
    }





    public boolean hasUnresolvedCardPayment(String customerId) {
        return org.demo.shopapp.cart.PurchaseIdentityService.blocked(jdbcTemplate,customerId);
    }

    public java.util.Optional<OrderRow> byPurchase(String customer,java.util.UUID purchase){
        var ids=jdbcTemplate.queryForList("SELECT id FROM shop_orders WHERE customer_id=? AND purchase_id=?",Long.class,customer,purchase);
        return ids.isEmpty()?java.util.Optional.empty():java.util.Optional.of(findById(ids.getFirst(),customer));
    }
    public void bindPurchase(long order,java.util.UUID purchase){
        if(jdbcTemplate.update("UPDATE shop_orders SET purchase_id=? WHERE id=? AND purchase_id IS NULL",purchase,order)!=1)
            throw new IllegalStateException("Purchase identity conflict.");
    }
    public boolean ownsCurrentCart(long order,String customer){
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS(SELECT 1 FROM shop_carts c JOIN shop_orders o ON o.customer_id=c.customer_id AND o.purchase_id=c.purchase_id WHERE o.id=? AND c.customer_id=? FOR UPDATE OF c)",Boolean.class,order,customer));
    }
    public void finishPurchase(long order,String customer){
        jdbcTemplate.update("UPDATE shop_carts c SET purchase_id=gen_random_uuid(),updated_at=CURRENT_TIMESTAMP FROM shop_orders o WHERE o.id=? AND o.customer_id=? AND c.customer_id=o.customer_id AND c.purchase_id=o.purchase_id",order,customer);
    }

    public void attachAuthorization(long orderId, String customerId, String authorizationId, String state) {
        jdbcTemplate.update(
                """
                UPDATE shop_orders
                SET bank_authorization_id = ?, payment_state = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND customer_id = ?
                """,
                authorizationId, state, orderId, customerId
        );
    }

    public void attachExternalPayment(long orderId, String customerId, String provider,
                                      String paymentId, String checkoutUrl) {
        jdbcTemplate.update(
                """
                UPDATE shop_orders
                SET payment_provider = ?, external_payment_id = ?, external_checkout_url = ?,
                    payment_state = 'CHECKOUT_CREATED', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND customer_id = ?
                """,
                provider, paymentId, checkoutUrl, orderId, customerId
        );
    }



    public OrderRow findByOrderNumber(String orderNumber) {
        return find(
                """
                SELECT id, order_number, customer_id, customer_account_number,
                       merchant_account_number, total, currency, status,
                       payment_idempotency_key, bank_authorization_id, payment_state,
                       bank_reference, failure_reason, payment_provider,
                       external_payment_id, external_checkout_url
                FROM shop_orders
                WHERE order_number = ?
                """,
                orderNumber
        );
    }















    public boolean claimCartCleanup(long orderId) {
        return jdbcTemplate.update(
                """
                UPDATE shop_orders
                SET cart_cleanup_completed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'PAID'
                  AND cart_cleanup_completed_at IS NULL
                  AND historical_imported_at IS NULL
                """,
                orderId
        ) == 1;
    }



    private OrderRow find(String sql, Object... arguments) {
        var rows = jdbcTemplate.query(sql, (rs, rowNum) -> new OrderRow(
                rs.getLong("id"),
                rs.getString("order_number"),
                rs.getString("customer_id"),
                rs.getString("customer_account_number"),
                rs.getString("merchant_account_number"),
                rs.getBigDecimal("total"),
                rs.getString("currency"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getString("payment_idempotency_key"),
                rs.getString("bank_authorization_id"),
                rs.getString("payment_state"),
                rs.getString("bank_reference"),
                rs.getString("failure_reason"),
                rs.getString("payment_provider"),
                rs.getString("external_payment_id"),
                rs.getString("external_checkout_url"),
                List.of()
        ), arguments);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found.");
        }
        var row = rows.getFirst();
        var items = jdbcTemplate.query(
                """
                SELECT product_id, sku, product_name, unit_price, quantity, line_total
                FROM shop_order_items
                WHERE order_id = ?
                ORDER BY id
                """,
                (rs, rowNum) -> new OrderItemResponse(
                        rs.getLong("product_id"),
                        rs.getString("sku"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("unit_price"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("line_total")
                ),
                row.id()
        );
        return row.withItems(items);
    }

    public record OrderRow(
            Long id,
            String orderNumber,
            String customerId,
            String customerAccountNumber,
            String merchantAccountNumber,
            BigDecimal total,
            String currency,
            OrderStatus status,
            String paymentIdempotencyKey,
            String bankAuthorizationId,
            String paymentState,
            String bankReference,
            String failureReason,
            String paymentProvider,
            String externalPaymentId,
            String externalCheckoutUrl,
            List<OrderItemResponse> items
    ) {
        public OrderRow(
                Long id, String orderNumber, String customerId, String customerAccountNumber,
                String merchantAccountNumber, BigDecimal total, String currency, OrderStatus status,
                String paymentIdempotencyKey, String bankAuthorizationId, String paymentState,
                String bankReference, String failureReason, List<OrderItemResponse> items
        ) {
            this(id, orderNumber, customerId, customerAccountNumber, merchantAccountNumber, total, currency,
                    status, paymentIdempotencyKey, bankAuthorizationId, paymentState, bankReference,
                    failureReason, null, null, null, items);
        }

        public OrderRow withItems(List<OrderItemResponse> value) {
            return new OrderRow(
                    id, orderNumber, customerId, customerAccountNumber,
                    merchantAccountNumber, total, currency, status,
                    paymentIdempotencyKey, bankAuthorizationId, paymentState,
                    bankReference, failureReason, paymentProvider,
                    externalPaymentId, externalCheckoutUrl, value
            );
        }
    }
}
