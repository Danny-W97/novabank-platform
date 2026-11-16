package org.demo.shopapp.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record BankCardQuoteResponse(
        BigDecimal amountPln,
        String currency,
        BigDecimal amount,
        BigDecimal rate,
        Instant expiresAt
) {
}
