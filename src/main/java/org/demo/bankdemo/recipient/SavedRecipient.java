package org.demo.bankdemo.recipient;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.demo.bankdemo.user.User;

import java.time.Instant;

@Entity
@Table(name = "saved_recipients")
public class SavedRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 100)
    private String alias;

    @Column(name = "recipient_name", nullable = false, length = 201)
    private String recipientName;

    @Column(name = "account_number", nullable = false, length = 26)
    private String accountNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SavedRecipient() {
    }

    public SavedRecipient(
            User owner,
            String alias,
            String recipientName,
            String accountNumber
    ) {
        this.owner = owner;
        this.alias = alias;
        this.recipientName = recipientName;
        this.accountNumber = accountNumber;
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

    public void update(String alias, String recipientName, String accountNumber) {
        this.alias = alias;
        this.recipientName = recipientName;
        this.accountNumber = accountNumber;
    }

    public Long getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public String getAlias() {
        return alias;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getAccountNumber() {
        return accountNumber;
    }
}
