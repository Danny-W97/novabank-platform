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
@Table(name = "login_history")
public class LoginHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(nullable = false)
    private boolean successful;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LoginHistory() {
    }

    public LoginHistory(
            Long userId,
            String email,
            String ipAddress,
            String userAgent,
            boolean successful,
            String failureReason
    ) {
        this.userId = userId;
        this.email = email;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.successful = successful;
        this.failureReason = failureReason;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Instant getCreatedAt() { return createdAt; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public boolean isSuccessful() { return successful; }
    public String getFailureReason() { return failureReason; }
}
