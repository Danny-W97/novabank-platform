package org.demo.shopapp.order;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record CardOrderPaymentRequest(
        @NotBlank @Pattern(regexp = "\\d{16}") String cardNumber,
        @NotBlank @Pattern(regexp = "\\d{3}") String cvv,
        @NotBlank @Pattern(regexp = "(0[1-9]|1[0-2])/\\d{2}") String expiry,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount
) {
}
