package org.demo.bankdemo.payment.authorization;

import jakarta.validation.constraints.NotBlank;

public record PaymentAuthorizationRedeemRequest(
        @NotBlank String code,
        @NotBlank String state
) {
}
