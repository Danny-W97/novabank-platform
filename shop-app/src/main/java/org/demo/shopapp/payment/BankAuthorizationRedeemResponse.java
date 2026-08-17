package org.demo.shopapp.payment;

import java.math.BigDecimal;

public record BankAuthorizationRedeemResponse(
        String orderId,
        BigDecimal amount,
        String currency,
        String status,
        String referenceNumber,
        String customerAccountNumber
) {
}
