package org.demo.shopapp.catalog;

import java.math.BigDecimal;

public record ProductResponse(
        Long id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        BigDecimal oldPrice,
        String currency,
        boolean active,
        String category,
        String emoji,
        String brand,
        String tag,
        Integer stars,
        Integer reviews
) {
}
