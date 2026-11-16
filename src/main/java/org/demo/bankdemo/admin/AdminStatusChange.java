package org.demo.bankdemo.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "admin_status_changes")
public class AdminStatusChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "account_number", nullable = false, length = 26)
    private String accountNumber;

    @Column(name = "user_email", nullable = false, length = 254)
    private String userEmail;

    @Column(name = "previous_account_status", length = 20)
    private String previousAccountStatus;

    @Column(name = "new_account_status", nullable = false, length = 20)
    private String newAccountStatus;

    @Column(name = "previous_user_status", length = 20)
    private String previousUserStatus;

    @Column(name = "new_user_status", nullable = false, length = 20)
    private String newUserStatus;

    @Column(name = "changed_by", nullable = false, length = 254)
    private String changedBy;

    @Column(nullable = false, length = 300)
    private String reason;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AdminStatusChange() {
    }

    public AdminStatusChange(
            Long accountId,
            String accountNumber,
            String userEmail,
            String previousAccountStatus,
            String newAccountStatus,
            String previousUserStatus,
            String newUserStatus,
            String changedBy,
            String reason,
            String ipAddress
    ) {
        this.accountId = accountId;
        this.accountNumber = accountNumber;
        this.userEmail = userEmail;
        this.previousAccountStatus = previousAccountStatus;
        this.newAccountStatus = newAccountStatus;
        this.previousUserStatus = previousUserStatus;
        this.newUserStatus = newUserStatus;
        this.changedBy = changedBy;
        this.reason = reason;
        this.ipAddress = ipAddress;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public String getAccountNumber() { return accountNumber; }
    public String getUserEmail() { return userEmail; }
    public String getPreviousAccountStatus() { return previousAccountStatus; }
    public String getNewAccountStatus() { return newAccountStatus; }
    public String getPreviousUserStatus() { return previousUserStatus; }
    public String getNewUserStatus() { return newUserStatus; }
    public String getChangedBy() { return changedBy; }
    public String getReason() { return reason; }
    public String getIpAddress() { return ipAddress; }
    public Instant getCreatedAt() { return createdAt; }
}
