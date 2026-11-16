package org.demo.bankdemo.currency;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Immutable, server-issued quote. Its primary key is also the execution idempotency key. */
@Entity
@Table(name = "exchange_quotes")
public class ExchangeQuote {
    @Id
    @Column(name = "idempotency_key", length = 64, nullable = false)
    private String idempotencyKey;

    @Column(name = "owner_email", length = 254, nullable = false)
    private String ownerEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_currency", length = 3, nullable = false)
    private CurrencyCode sourceCurrency;
    @Enumerated(EnumType.STRING)
    @Column(name = "target_currency", length = 3, nullable = false)
    private CurrencyCode targetCurrency;
    @Column(name = "source_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal sourceAmount;
    @Column(name = "target_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal targetAmount;
    @Column(name = "applied_rate", precision = 19, scale = 8, nullable = false)
    private BigDecimal appliedRate;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExchangeQuote() {}

    public ExchangeQuote(String key, String email, ExchangeService.Quote quote) {
        idempotencyKey = key;
        ownerEmail = email;
        sourceCurrency = quote.sourceCurrency();
        targetCurrency = quote.targetCurrency();
        sourceAmount = quote.sourceAmount();
        targetAmount = quote.targetAmount();
        appliedRate = quote.appliedRate();
        createdAt = quote.createdAt();
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public String getOwnerEmail() { return ownerEmail; }
    public ExchangeService.Quote toQuote() {
        return new ExchangeService.Quote(sourceCurrency, targetCurrency, sourceAmount, targetAmount,
                appliedRate, createdAt);
    }
}
