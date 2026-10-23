package org.demo.shopapp.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record BankAuthorizationStatusResponse(
        String authorizationId,
        String shopOrderId,
        BigDecimal amount,
        String currency,
        String status,
        String referenceNumber,
        String customerAccountNumber,
        String failureReason,
        Instant expiresAt,
        String merchantId
) {
}
