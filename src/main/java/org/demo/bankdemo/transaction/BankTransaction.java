package org.demo.bankdemo.transaction;

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
@Table(name = "bank_transactions")
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reference_number", nullable = false, length = 32, unique = true)
    private String referenceNumber;

    @Column(name = "idempotency_key", nullable = false, length = 64, unique = true)
    private String idempotencyKey;

    @Column(name = "payment_order_id", length = 100)
    private String paymentOrderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_account_id")
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_account_id", nullable = false)
    private Account destinationAccount;

    @Column(name = "sender_name_snapshot", length = 201)
    private String senderNameSnapshot;

    @Column(name = "recipient_name_snapshot", nullable = false, length = 201)
    private String recipientNameSnapshot;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 140)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected BankTransaction() {
    }

    private BankTransaction(
            String referenceNumber,
            String idempotencyKey,
            Account destinationAccount,
            String recipientNameSnapshot,
            BigDecimal amount
    ) {
        this.referenceNumber = referenceNumber;
        this.idempotencyKey = idempotencyKey;
        this.destinationAccount = destinationAccount;
        this.recipientNameSnapshot = recipientNameSnapshot;
        this.amount = amount;
        this.currency = destinationAccount.getCurrency();
        this.title = "Saldo startowe";
        this.type = TransactionType.INITIAL_CREDIT;
        this.status = TransactionStatus.COMPLETED;
    }

    public static BankTransaction initialCredit(
            String referenceNumber,
            String idempotencyKey,
            Account destinationAccount,
            BigDecimal amount
    ) {
        return new BankTransaction(
                referenceNumber,
                idempotencyKey,
                destinationAccount,
                destinationAccount.getOwner().getFullName(),
                amount
        );
    }

    public static BankTransaction transfer(
            String referenceNumber,
            String idempotencyKey,
            Account sourceAccount,
            Account destinationAccount,
            String senderNameSnapshot,
            String recipientNameSnapshot,
            BigDecimal amount,
            String title,
            Instant completedAt
    ) {
        var transaction = new BankTransaction();
        transaction.referenceNumber = referenceNumber;
        transaction.idempotencyKey = idempotencyKey;
        transaction.sourceAccount = sourceAccount;
        transaction.destinationAccount = destinationAccount;
        transaction.senderNameSnapshot = senderNameSnapshot;
        transaction.recipientNameSnapshot = recipientNameSnapshot;
        transaction.amount = amount;
        transaction.currency = sourceAccount.getCurrency();
        transaction.title = title;
        transaction.type = TransactionType.TRANSFER;
        transaction.status = TransactionStatus.COMPLETED;
        transaction.createdAt = completedAt;
        transaction.completedAt = completedAt;
        return transaction;
    }

    public static BankTransaction payment(
            String referenceNumber,
            String idempotencyKey,
            String paymentOrderId,
            Account sourceAccount,
            Account destinationAccount,
            String senderNameSnapshot,
            String recipientNameSnapshot,
            BigDecimal amount,
            String title,
            Instant completedAt
    ) {
        var transaction = new BankTransaction();
        transaction.referenceNumber = referenceNumber;
        transaction.idempotencyKey = idempotencyKey;
        transaction.paymentOrderId = paymentOrderId;
        transaction.sourceAccount = sourceAccount;
        transaction.destinationAccount = destinationAccount;
        transaction.senderNameSnapshot = senderNameSnapshot;
        transaction.recipientNameSnapshot = recipientNameSnapshot;
        transaction.amount = amount;
        transaction.currency = sourceAccount.getCurrency();
        transaction.title = title;
        transaction.type = TransactionType.PAYMENT;
        transaction.status = TransactionStatus.COMPLETED;
        transaction.createdAt = completedAt;
        transaction.completedAt = completedAt;
        return transaction;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == TransactionStatus.COMPLETED && completedAt == null) {
            completedAt = createdAt;
        }
    }

    public Long getId() {
        return id;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getPaymentOrderId() {
        return paymentOrderId;
    }

    public Account getSourceAccount() {
        return sourceAccount;
    }

    public Account getDestinationAccount() {
        return destinationAccount;
    }

    public String getSenderNameSnapshot() {
        return senderNameSnapshot;
    }

    public String getRecipientNameSnapshot() {
        return recipientNameSnapshot;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getTitle() {
        return title;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
