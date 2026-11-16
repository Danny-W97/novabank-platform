package org.demo.bankdemo.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.demo.bankdemo.user.User;

import java.time.Instant;

@Entity
@Table(name = "totp_recovery_codes")
public class TotpRecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TotpRecoveryCode() {
    }

    public TotpRecoveryCode(User user, String codeHash, Instant createdAt) {
        this.user = user;
        this.codeHash = codeHash;
        this.createdAt = createdAt;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public void markUsed(Instant usedAt) {
        this.usedAt = usedAt;
    }
}
