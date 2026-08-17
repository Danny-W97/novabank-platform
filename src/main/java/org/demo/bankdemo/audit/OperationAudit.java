package org.demo.bankdemo.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "operation_audits")
public class OperationAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_email", length = 254)
    private String userEmail;

    @Column(name = "target_user_email", length = 254)
    private String targetUserEmail;

    @Column(name = "target_account_id")
    private Long targetAccountId;

    @Column(nullable = false, length = 40)
    private String operation;

    @Column(nullable = false, length = 300)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "admin_action", nullable = false)
    private boolean adminAction;

    protected OperationAudit() {
    }

    public OperationAudit(String userEmail, String operation, String reason) {
        this.userEmail = userEmail;
        this.operation = operation;
        this.reason = reason;
        this.success = false;
        this.adminAction = false;
    }

    public OperationAudit(
            String actorEmail,
            String operation,
            String reason,
            String targetUserEmail,
            Long targetAccountId,
            String ipAddress,
            boolean success,
            boolean adminAction
    ) {
        this.userEmail = actorEmail;
        this.operation = operation;
        this.reason = reason;
        this.targetUserEmail = targetUserEmail;
        this.targetAccountId = targetAccountId;
        this.ipAddress = ipAddress;
        this.success = success;
        this.adminAction = adminAction;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public String getTargetUserEmail() {
        return targetUserEmail;
    }

    public Long getTargetAccountId() {
        return targetAccountId;
    }

    public String getOperation() {
        return operation;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isAdminAction() {
        return adminAction;
    }
}
