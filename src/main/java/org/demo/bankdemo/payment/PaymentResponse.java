package org.demo.bankdemo.payment;

import java.math.BigDecimal;

public record PaymentResponse(
        String referenceNumber,
        String orderId,
        BigDecimal amount,
        String currency,
        String status,
        boolean replayed
) {
}
