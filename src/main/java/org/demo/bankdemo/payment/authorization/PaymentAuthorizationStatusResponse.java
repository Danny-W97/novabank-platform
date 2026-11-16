package org.demo.bankdemo.payment.authorization;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentAuthorizationStatusResponse(
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

    public static PaymentAuthorizationStatusResponse from(PaymentAuthorization authorization) {
        return new PaymentAuthorizationStatusResponse(
                authorization.getId(),
                authorization.getShopOrderId(),
                authorization.getAmount(),
                authorization.getCurrency(),
                authorization.getStatus().name(),
                authorization.getBankReference(),
                authorization.getSourceAccountNumber(),
                authorization.getFailureReason(),
                authorization.getExpiresAt(),
                authorization.getMerchantId()
        );
    }
}
