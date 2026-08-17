package org.demo.bankdemo.card;

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

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "card_transactions")
public class CardTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false)
    private DemoCard card;

    @Column(name = "merchant_name", nullable = false, length = 120)
    private String merchantName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private org.demo.bankdemo.currency.CurrencyCode currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "amount_pln", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountInPln;

    @Column(name = "idempotency_key", nullable = false, length = 64, unique = true)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardTransactionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardPaymentChannel channel;

    @Column(length = 300)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CardTransaction() {
    }

    public CardTransaction(
            DemoCard card,
            String merchantName,
            org.demo.bankdemo.currency.CurrencyCode currency,
            BigDecimal amount,
            BigDecimal amountInPln,
            String idempotencyKey,
            CardPaymentChannel channel,
            CardTransactionStatus status,
            String reason
    ) {
        this.card = card;
        this.merchantName = merchantName;
        this.currency = currency;
        this.amount = amount;
        this.amountInPln = amountInPln;
        this.idempotencyKey = idempotencyKey;
        this.channel = channel;
        this.status = status;
        this.reason = reason;
    }

    @PrePersist
    void onCreate() { createdAt = Instant.now(); }

    public DemoCard getCard() { return card; }
    public String getMerchantName() { return merchantName; }
    public org.demo.bankdemo.currency.CurrencyCode getCurrency() { return currency; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getAmountInPln() { return amountInPln; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public CardPaymentChannel getChannel() { return channel; }
    public CardTransactionStatus getStatus() { return status; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
