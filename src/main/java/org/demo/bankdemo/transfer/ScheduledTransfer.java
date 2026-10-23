package org.demo.bankdemo.transfer;

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
import org.demo.bankdemo.user.User;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;

@Entity
@Table(name = "scheduled_transfers")
public class ScheduledTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "idempotency_key", nullable = false, length = 64, unique = true)
    private String idempotencyKey;

    @Column(name = "first_execution_at", nullable = false)
    private Instant firstExecutionAt;

    @Column(name = "destination_account_number", nullable = false, length = 26)
    private String destinationAccountNumber;

    @Column(name = "recipient_name", nullable = false, length = 201)
    private String recipientName;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 140)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ScheduleFrequency frequency;

    @Column(name = "next_execution_at", nullable = false)
    private Instant nextExecutionAt;

    @Column(name = "scheduled_for_at", nullable = false)
    private Instant scheduledForAt;

    @Column(name = "execution_day_of_month")
    private Integer executionDayOfMonth;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "last_executed_at")
    private Instant lastExecutedAt;

    @Column(name = "last_error", length = 300)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ScheduledTransfer() {
    }

    public ScheduledTransfer(
            User user,
            String idempotencyKey,
            String destinationAccountNumber,
            String recipientName,
            BigDecimal amount,
            String title,
            ScheduleFrequency frequency,
            Instant nextExecutionAt
    ) {
        this.user = user;
        this.idempotencyKey = idempotencyKey;
        this.firstExecutionAt = nextExecutionAt;
        this.destinationAccountNumber = destinationAccountNumber;
        this.recipientName = recipientName;
        this.amount = amount;
        this.title = title;
        this.frequency = frequency;
        this.nextExecutionAt = nextExecutionAt;
        this.scheduledForAt = nextExecutionAt;
        this.executionDayOfMonth = frequency == ScheduleFrequency.MONTHLY
                ? nextExecutionAt.atZone(ZoneId.of("Europe/Warsaw")).getDayOfMonth() : null;
        this.active = true;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getFirstExecutionAt() { return firstExecutionAt; }
    public String getDestinationAccountNumber() { return destinationAccountNumber; }
    public String getRecipientName() { return recipientName; }
    public BigDecimal getAmount() { return amount; }
    public String getTitle() { return title; }
    public ScheduleFrequency getFrequency() { return frequency; }
    public Instant getNextExecutionAt() { return nextExecutionAt; }
    public Instant getScheduledForAt() { return scheduledForAt; }
    public Integer getExecutionDayOfMonth() { return executionDayOfMonth; }
    public boolean isActive() { return active; }
    public Instant getLastExecutedAt() { return lastExecutedAt; }
    public String getLastError() { return lastError; }

    public void markExecuted(Instant executedAt, Instant nextExecutionAt) {
        this.lastExecutedAt = executedAt;
        this.nextExecutionAt = nextExecutionAt;
        if (frequency != ScheduleFrequency.ONCE) {
            this.scheduledForAt = nextExecutionAt;
        }
        this.lastError = null;
        if (frequency == ScheduleFrequency.ONCE) {
            this.active = false;
        }
    }

    public void markFailed(String error, Instant retryAt) {
        this.lastError = error == null ? "Nieznany błąd wykonania." : error.substring(0, Math.min(300, error.length()));
        this.nextExecutionAt = retryAt;
    }

    public void cancel() { this.active = false; }
}
