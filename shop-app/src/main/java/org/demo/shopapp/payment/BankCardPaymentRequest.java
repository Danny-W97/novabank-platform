package org.demo.shopapp.payment;

import java.math.BigDecimal;

public record BankCardPaymentRequest(
        String cardNumber,
        String cvv,
        String expiry,
        String merchantAccountNumber,
        String orderId,
        String currency,
        BigDecimal amountPln,
        BigDecimal amount,
        String title
) {
}
