package org.demo.bankdemo.payment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record RefundRequest(
        @NotBlank @Pattern(regexp = "\\d{26}") String merchantAccountNumber,
        @Pattern(regexp = "\\d{26}") String customerAccountNumber,
        @NotBlank @Size(max = 100) String orderId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotBlank @Size(max = 140) String reason,
        @Pattern(regexp = "[A-Z0-9-]{8,32}") String originalReferenceNumber
) {
}
