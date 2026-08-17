package org.demo.shopapp.payment;

import java.time.Instant;

public record BankAuthorizationResponse(
        String authorizationId,
        String authorizationUrl,
        String state,
        Instant expiresAt
) {
}
