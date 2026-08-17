package org.demo.bankdemo.card;

import java.math.BigDecimal;

public record CardPaymentResponse(
        String referenceNumber,
        String orderId,
        BigDecimal amount,
        String currency,
        BigDecimal amountInPln,
        String status,
        String reason,
        String sourceAccountNumber,
        boolean replayed
) {

    public static CardPaymentResponse from(String orderId, CardService.OnlinePaymentResult result) {
        return new CardPaymentResponse(
                result.referenceNumber(),
                orderId,
                result.amount(),
                result.currency().name(),
                result.amountInPln(),
                result.status().name(),
                result.reason(),
                result.sourceAccountNumber(),
                result.replayed()
        );
    }
}
