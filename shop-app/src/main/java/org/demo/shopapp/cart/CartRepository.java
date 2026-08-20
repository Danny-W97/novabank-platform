package org.demo.shopapp.cart;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class CartRepository {

    private final JdbcTemplate jdbcTemplate;

    public CartRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long ensureCart(String customerId) {
        var existing = jdbcTemplate.query(
                "SELECT id FROM shop_carts WHERE customer_id = ?",
                (rs, rowNum) -> rs.getLong(1),
                customerId
        );
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO shop_carts (customer_id) VALUES (?)",
                    new String[]{"id"}
            );
            statement.setString(1, customerId);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void addItem(long cartId, long productId, int quantity) {
        jdbcTemplate.update(
                """
                INSERT INTO shop_cart_items (cart_id, product_id, quantity)
                VALUES (?, ?, ?)
                ON CONFLICT (cart_id, product_id)
                DO UPDATE SET quantity = shop_cart_items.quantity + EXCLUDED.quantity
                """,
                cartId, productId, quantity
        );
        touch(cartId);
    }

    public void setItemQuantity(long cartId, long productId, int quantity) {
        jdbcTemplate.update(
                """
                INSERT INTO shop_cart_items (cart_id, product_id, quantity)
                VALUES (?, ?, ?)
                ON CONFLICT (cart_id, product_id)
                DO UPDATE SET quantity = EXCLUDED.quantity
                """,
                cartId, productId, quantity
        );
        touch(cartId);
    }

    public List<CartItemResponse> findItems(String customerId) {
        return jdbcTemplate.query(
                """
                SELECT p.id, p.sku, p.name, p.price, p.currency, ci.quantity,
                       p.price * ci.quantity AS line_total
                FROM shop_carts c
                JOIN shop_cart_items ci ON ci.cart_id = c.id
                JOIN shop_products p ON p.id = ci.product_id
                WHERE c.customer_id = ? AND p.active = TRUE
                ORDER BY p.id
                """,
                (rs, rowNum) -> new CartItemResponse(
                        rs.getLong("id"),
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getBigDecimal("price"),
                        rs.getString("currency"),
                        rs.getInt("quantity"),
                        rs.getBigDecimal("line_total")
                ),
                customerId
        );
    }

    public void removeItem(String customerId, long productId) {
        jdbcTemplate.update(
                """
                DELETE FROM shop_cart_items
                WHERE cart_id = (SELECT id FROM shop_carts WHERE customer_id = ?)
                  AND product_id = ?
                """,
                customerId, productId
        );
    }

    public void clear(String customerId) {
        jdbcTemplate.update(
                "DELETE FROM shop_cart_items WHERE cart_id = (SELECT id FROM shop_carts WHERE customer_id = ?)",
                customerId
        );
    }

    public void removePurchasedQuantities(String customerId, Map<Long, Integer> purchasedQuantities) {
        if (purchasedQuantities.isEmpty()) {
            return;
        }
        for (var entry : purchasedQuantities.entrySet()) {
            var productId = entry.getKey();
            var quantity = entry.getValue();
            jdbcTemplate.update(
                    """
                    DELETE FROM shop_cart_items
                    WHERE cart_id = (SELECT id FROM shop_carts WHERE customer_id = ?)
                      AND product_id = ?
                      AND quantity <= ?
                    """,
                    customerId, productId, quantity
            );
            jdbcTemplate.update(
                    """
                    UPDATE shop_cart_items
                    SET quantity = quantity - ?
                    WHERE cart_id = (SELECT id FROM shop_carts WHERE customer_id = ?)
                      AND product_id = ?
                      AND quantity > ?
                    """,
                    quantity, customerId, productId, quantity
            );
        }
        jdbcTemplate.update(
                """
                UPDATE shop_carts
                SET updated_at = CURRENT_TIMESTAMP
                WHERE customer_id = ?
                """,
                customerId
        );
    }

    private void touch(long cartId) {
        jdbcTemplate.update(
                "UPDATE shop_carts SET updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                cartId
        );
    }
}
