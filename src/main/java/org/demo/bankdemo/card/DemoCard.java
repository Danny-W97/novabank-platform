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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.demo.bankdemo.account.Account;

import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;

@Entity
@Table(name = "demo_cards")
public class DemoCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    private Account account;

    @Column(name = "masked_number", nullable = false, length = 32, unique = true)
    private String maskedNumber;

    @Column(name = "online_number", nullable = false, length = 16, unique = true)
    private String onlineNumber;

    // Demo-only payment metadata. Real card CVV must never be stored this way.
    @Column(name = "demo_cvv", nullable = false, length = 3)
    private String demoCvv;

    @Column(name = "demo_expires_at", nullable = false)
    private LocalDate demoExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardStatus status;

    @Column(name = "daily_limit", nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyLimit;

    @Column(name = "blocked_until")
    private Instant blockedUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DemoCard() {
    }

    public DemoCard(Account account, String maskedNumber) {
        this.account = account;
        this.maskedNumber = maskedNumber;
        this.onlineNumber = demoOnlineNumber();
        this.demoCvv = "%03d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(100, 1000));
        this.demoExpiresAt = LocalDate.now().plusYears(3).withDayOfMonth(1).plusMonths(1).minusDays(1);
        this.status = CardStatus.ACTIVE;
        this.dailyLimit = new BigDecimal("5000.00");
    }

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public String getMaskedNumber() { return maskedNumber; }
    public String getOnlineNumber() { return onlineNumber; }
    public String getDemoCvv() { return demoCvv; }
    public LocalDate getDemoExpiresAt() { return demoExpiresAt; }
    public CardStatus getStatus() { return status; }
    public BigDecimal getDailyLimit() { return dailyLimit; }
    public Instant getBlockedUntil() { return blockedUntil; }

    public void block() {
        status = CardStatus.BLOCKED;
        blockedUntil = null;
    }

    public void temporarilyBlock(Instant until) {
        status = CardStatus.TEMPORARILY_BLOCKED;
        blockedUntil = until;
    }

    public void activate() {
        status = CardStatus.ACTIVE;
        blockedUntil = null;
    }

    public void updateDailyLimit(BigDecimal dailyLimit) {
        if (dailyLimit == null || dailyLimit.signum() <= 0 || dailyLimit.scale() > 2
                || dailyLimit.precision() > 19) {
            throw new CardException("Limit dzienny karty musi byc dodatni i miec maksymalnie dwa miejsca po przecinku.");
        }
        this.dailyLimit = dailyLimit.setScale(2);
    }

    public CardStatus statusAt(Instant now) {
        if (status == CardStatus.TEMPORARILY_BLOCKED
                && (blockedUntil == null || !blockedUntil.isAfter(now))) {
            activate();
        }
        return status;
    }

    private static String demoOnlineNumber() {
        return "%016d".formatted(java.util.concurrent.ThreadLocalRandom.current()
                .nextLong(1_000_000_000_000_000L, 9_999_999_999_999_999L));
    }
}
