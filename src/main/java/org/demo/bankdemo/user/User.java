package org.demo.bankdemo.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    @Column(name = "transaction_pin_hash", length = 100)
    private String transactionPinHash;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(length = 32)
    private String phone;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "totp_secret_encrypted", columnDefinition = "TEXT")
    private String totpSecretEncrypted;

    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled;

    @Column(name = "totp_enabled_at")
    private Instant totpEnabledAt;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "transfer_single_limit", precision = 19, scale = 2)
    private BigDecimal transferSingleLimit;

    @Column(name = "transfer_daily_limit", precision = 19, scale = 2)
    private BigDecimal transferDailyLimit;

    @Column(name = "payment_single_limit", precision = 19, scale = 2)
    private BigDecimal paymentSingleLimit;

    @Column(name = "payment_daily_limit", precision = 19, scale = 2)
    private BigDecimal paymentDailyLimit;

    @Column(name = "totp_required_above", precision = 19, scale = 2)
    private BigDecimal totpRequiredAbove;

    protected User() {
    }

    public User(
            String email,
            String passwordHash,
            String firstName,
            String lastName,
            String phone
    ) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
        this.role = UserRole.USER;
        this.status = UserStatus.ACTIVE;
        this.emailVerified = false;
    }

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (passwordChangedAt == null) {
            passwordChangedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void recordSuccessfulLogin(Instant loginTime) {
        lastLoginAt = loginTime;
    }

    public void updateProfile(
            String firstName,
            String lastName,
            String phone,
            LocalDate birthDate
    ) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
        this.birthDate = birthDate;
    }

    public void changePassword(String passwordHash, Instant changedAt) {
        this.passwordHash = passwordHash;
        this.passwordChangedAt = changedAt;
    }

    public void changeTransactionPin(String transactionPinHash) {
        this.transactionPinHash = transactionPinHash;
    }

    public void changeRole(UserRole role) {
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public String getTransactionPinHash() {
        return transactionPinHash;
    }

    public boolean hasTransactionPin() {
        return transactionPinHash != null && !transactionPinHash.isBlank();
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getPhone() {
        return phone;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public UserRole getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void block() {
        status = UserStatus.BLOCKED;
    }

    public void activate() {
        status = UserStatus.ACTIVE;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public void verifyEmail(Instant verifiedAt) {
        this.emailVerified = true;
        this.emailVerifiedAt = verifiedAt;
    }

    public boolean isTotpEnabled() {
        return totpEnabled;
    }

    public String getTotpSecretEncrypted() {
        return totpSecretEncrypted;
    }

    public void enableTotp(String encryptedSecret, Instant enabledAt) {
        this.totpSecretEncrypted = encryptedSecret;
        this.totpEnabled = true;
        this.totpEnabledAt = enabledAt;
    }

    public void disableTotp() {
        this.totpSecretEncrypted = null;
        this.totpEnabled = false;
        this.totpEnabledAt = null;
    }

    public void updateLimits(
            BigDecimal transferSingleLimit,
            BigDecimal transferDailyLimit,
            BigDecimal paymentSingleLimit,
            BigDecimal paymentDailyLimit
    ) {
        this.transferSingleLimit = transferSingleLimit;
        this.transferDailyLimit = transferDailyLimit;
        this.paymentSingleLimit = paymentSingleLimit;
        this.paymentDailyLimit = paymentDailyLimit;
    }

    public void updateTotpRequiredAbove(BigDecimal totpRequiredAbove) {
        this.totpRequiredAbove = totpRequiredAbove;
    }

    public BigDecimal getTransferSingleLimit() { return transferSingleLimit; }
    public BigDecimal getTransferDailyLimit() { return transferDailyLimit; }
    public BigDecimal getPaymentSingleLimit() { return paymentSingleLimit; }
    public BigDecimal getPaymentDailyLimit() { return paymentDailyLimit; }
    public BigDecimal getTotpRequiredAbove() { return totpRequiredAbove; }

    public String getFullName() {
        return firstName + " " + lastName;
    }
}
