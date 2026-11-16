package org.demo.bankdemo.card;

import org.demo.bankdemo.currency.CurrencyCode;
import org.demo.bankdemo.currency.ExchangeRateRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** PLN valuation for simulated card limits. Never treats a missing foreign rate as 1:1. */
final class CardCurrencyValuation {
    private final ExchangeRateRepository rates;
    CardCurrencyValuation(ExchangeRateRepository rates) {this.rates = Objects.requireNonNull(rates);}
    BigDecimal inPln(CurrencyCode currency, BigDecimal amount) {
        return amount.multiply(rate(currency)).setScale(2, RoundingMode.HALF_UP);
    }
    BigDecimal fromPln(CurrencyCode currency, BigDecimal amount) {
        return amount.divide(rate(currency), 2, RoundingMode.HALF_UP);
    }
    private BigDecimal rate(CurrencyCode currency) {
        var rate = currency == CurrencyCode.PLN ? BigDecimal.ONE : rates.findById(currency)
                .map(exchangeRate -> exchangeRate.getRateToPln())
                .orElseThrow(() -> new CardException("Brak aktywnego kursu dla wybranej waluty."));
        if (rate.signum() <= 0) throw new CardException("Nieprawidłowy kurs wybranej waluty.");
        return rate;
    }
}
