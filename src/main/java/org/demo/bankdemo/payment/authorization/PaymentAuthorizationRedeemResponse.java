package org.demo.bankdemo.payment.authorization;

import java.math.BigDecimal;

public record PaymentAuthorizationRedeemResponse(
        String orderId,
        BigDecimal amount,
        String currency,
        String status,
        String referenceNumber,
        String customerAccountNumber
) {
}
