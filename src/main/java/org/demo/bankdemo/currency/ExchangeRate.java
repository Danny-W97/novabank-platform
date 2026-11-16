package org.demo.bankdemo.currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "exchange_rates")
public class ExchangeRate {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 3)
    private CurrencyCode currency;

    @Column(name = "rate_to_pln", nullable = false, precision = 19, scale = 6)
    private BigDecimal rateToPln;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExchangeRate() {
    }

    public ExchangeRate(CurrencyCode currency, BigDecimal rateToPln) {
        this.currency = currency;
        this.rateToPln = rateToPln;
        this.active = true;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public BigDecimal getRateToPln() {
        return rateToPln;
    }

    public boolean isActive() {
        return active;
    }

    public void updateRate(BigDecimal rateToPln) {
        this.rateToPln = rateToPln;
        this.active = true;
    }
}
