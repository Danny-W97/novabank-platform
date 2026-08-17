package org.demo.shopapp.payment;

import java.math.BigDecimal;

public record BankCardPaymentResponse(
        String referenceNumber,
        String orderId,
        BigDecimal amount,
        String currency,
        BigDecimal amountInPln,
        String status,
        String reason,
        String sourceAccountNumber,
        boolean replayed
) {
}
