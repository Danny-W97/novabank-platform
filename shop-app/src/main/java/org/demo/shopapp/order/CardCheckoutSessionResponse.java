package org.demo.shopapp.order;

import java.math.BigDecimal;
import java.time.Instant;

public record CardCheckoutSessionResponse(
        Long orderId,
        String orderNumber,
        BigDecimal amount,
        String currency,
        String idempotencyKey,
        Instant expiresAt,
        String redirectUrl
) {
}
