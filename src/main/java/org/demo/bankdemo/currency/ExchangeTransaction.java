package org.demo.bankdemo.currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.demo.bankdemo.account.Account;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "exchange_transactions")
public class ExchangeTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_currency", nullable = false, length = 3)
    private CurrencyCode sourceCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_currency", nullable = false, length = 3)
    private CurrencyCode targetCurrency;

    @Column(name = "source_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal sourceAmount;

    @Column(name = "target_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal targetAmount;

    @Column(name = "applied_rate", nullable = false, precision = 19, scale = 8)
    private BigDecimal appliedRate;

    @Column(name = "idempotency_key", nullable = false, length = 64, unique = true)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExchangeStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExchangeTransaction() {
    }

    public ExchangeTransaction(
            Account account,
            CurrencyCode sourceCurrency,
            CurrencyCode targetCurrency,
            BigDecimal sourceAmount,
            BigDecimal targetAmount,
            BigDecimal appliedRate,
            String idempotencyKey
    ) {
        this.account = account;
        this.sourceCurrency = sourceCurrency;
        this.targetCurrency = targetCurrency;
        this.sourceAmount = sourceAmount;
        this.targetAmount = targetAmount;
        this.appliedRate = appliedRate;
        this.idempotencyKey = idempotencyKey;
        this.status = ExchangeStatus.COMPLETED;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public CurrencyCode getSourceCurrency() { return sourceCurrency; }
    public CurrencyCode getTargetCurrency() { return targetCurrency; }
    public BigDecimal getSourceAmount() { return sourceAmount; }
    public BigDecimal getTargetAmount() { return targetAmount; }
    public BigDecimal getAppliedRate() { return appliedRate; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public ExchangeStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
