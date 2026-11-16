package org.demo.bankdemo.currency;

import java.io.Serializable;
import java.math.BigDecimal;

public class ExchangeForm implements Serializable {

    private CurrencyCode sourceCurrency = CurrencyCode.PLN;
    private CurrencyCode targetCurrency = CurrencyCode.EUR;
    private String amount;
    private String idempotencyKey;

    public BigDecimal amountValue() {
        if (amount == null || amount.isBlank()) {
            throw new ExchangeException("amount", "Podaj kwotę wymiany.");
        }
        try {
            return new BigDecimal(amount.strip().replace(',', '.'));
        } catch (NumberFormatException exception) {
            throw new ExchangeException("amount", "Podaj poprawną kwotę wymiany.");
        }
    }

    public CurrencyCode getSourceCurrency() { return sourceCurrency; }
    public void setSourceCurrency(CurrencyCode sourceCurrency) { this.sourceCurrency = sourceCurrency; }
    public CurrencyCode getTargetCurrency() { return targetCurrency; }
    public void setTargetCurrency(CurrencyCode targetCurrency) { this.targetCurrency = targetCurrency; }
    public String getAmount() { return amount; }
    public void setAmount(String amount) { this.amount = amount; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
