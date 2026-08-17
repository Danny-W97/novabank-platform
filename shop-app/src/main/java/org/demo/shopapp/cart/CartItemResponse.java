package org.demo.shopapp.cart;

import java.math.BigDecimal;

public record CartItemResponse(
        Long productId,
        String sku,
        String name,
        BigDecimal unitPrice,
        String currency,
        int quantity,
        BigDecimal lineTotal
) {
}
