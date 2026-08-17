package org.demo.bankdemo.card;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.demo.bankdemo.currency.CurrencyCode;

import java.math.BigDecimal;

public record CardPaymentRequest(
        @NotBlank @Pattern(regexp = "\\d{16}") String cardNumber,
        @NotBlank @Pattern(regexp = "\\d{3}") String cvv,
        @NotBlank @Pattern(regexp = "(0[1-9]|1[0-2])/\\d{2}") String expiry,
        @NotBlank @Pattern(regexp = "\\d{26}") String merchantAccountNumber,
        @NotBlank @Size(max = 100) String orderId,
        @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amountPln,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 140) String title
) {

    public CurrencyCode currencyCode() {
        try {
            return CurrencyCode.valueOf(currency);
        } catch (IllegalArgumentException exception) {
            throw new CardException("Nieobslugiwany kod waluty.");
        }
    }
}
