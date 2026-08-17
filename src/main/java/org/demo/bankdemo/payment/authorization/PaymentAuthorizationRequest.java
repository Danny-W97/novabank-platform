package org.demo.bankdemo.payment.authorization;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PaymentAuthorizationRequest(
        @NotBlank @Size(max = 100) String shopOrderId,
        @NotBlank @Size(max = 100) String shopCustomerId,
        @NotBlank @Pattern(regexp = "\\d{26}") String merchantAccountNumber,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Size(max = 140) String title,
        @NotBlank @Size(max = 64) String paymentIdempotencyKey,
        @NotBlank @Size(max = 128) String state
) {
}
