package org.demo.shopapp.payment;

import java.math.BigDecimal;

public record BankAuthorizationRequest(
        String shopOrderId,
        String shopCustomerId,
        String merchantAccountNumber,
        BigDecimal amount,
        String currency,
        String title,
        String paymentIdempotencyKey,
        String state
) {
}
