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
        boolean replayed,
        String merchantId
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
                result.replayed(),
                null
        );
    }

    public CardPaymentResponse withMerchant(String merchantId) {
        return new CardPaymentResponse(referenceNumber,orderId,amount,currency,amountInPln,status,reason,
                sourceAccountNumber,replayed,merchantId);
    }
}
