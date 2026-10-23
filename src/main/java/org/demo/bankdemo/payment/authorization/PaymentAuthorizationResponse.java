package org.demo.bankdemo.payment.authorization;

import java.time.Instant;

public record PaymentAuthorizationResponse(
        String authorizationId,
        String authorizationUrl,
        String state,
        Instant expiresAt,
        String merchantId
) {
}
