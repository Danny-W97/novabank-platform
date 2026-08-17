package org.demo.shopapp.payment;

import java.math.BigDecimal;

public record BankRefundRequest(
        String merchantAccountNumber,
        String customerAccountNumber,
        String orderId,
        BigDecimal amount,
        String currency,
        String reason,
        String originalReferenceNumber
) {
}
