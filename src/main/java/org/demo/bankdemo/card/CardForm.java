package org.demo.bankdemo.card;

import org.demo.bankdemo.currency.CurrencyCode;

import java.io.Serializable;
import java.math.BigDecimal;

public class CardForm implements Serializable {

    private String merchantName;
    private CurrencyCode currency = CurrencyCode.EUR;
    private CardPaymentChannel channel = CardPaymentChannel.IN_STORE;
    private String amount;
    private String idempotencyKey;

    public BigDecimal amountValue() {
        if (amount == null || amount.isBlank()) {
            throw new CardException("Podaj kwotę transakcji.");
        }
        try {
            return new BigDecimal(amount.strip().replace(',', '.'));
        } catch (NumberFormatException exception) {
            throw new CardException("Podaj poprawną kwotę transakcji.");
        }
    }

    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public CurrencyCode getCurrency() { return currency; }
    public void setCurrency(CurrencyCode currency) { this.currency = currency; }
    public CardPaymentChannel getChannel() { return channel; }
    public void setChannel(CardPaymentChannel channel) { this.channel = channel; }
    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
