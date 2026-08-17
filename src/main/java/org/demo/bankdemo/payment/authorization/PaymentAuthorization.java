package org.demo.bankdemo.payment.authorization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment_authorizations")
public class PaymentAuthorization {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "shop_order_id", nullable = false, length = 100)
    private String shopOrderId;

    @Column(name = "shop_customer_id", nullable = false, length = 100)
    private String shopCustomerId;

    @Column(name = "merchant_account_number", nullable = false, length = 26)
    private String merchantAccountNumber;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 140)
    private String title;

    @Column(name = "payment_idempotency_key", nullable = false, unique = true, length = 64)
    private String paymentIdempotencyKey;

    @Column(nullable = false, length = 128)
    private String state;

    @Column(name = "return_url", nullable = false, length = 500)
    private String returnUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuthorizationStatus status;

    @Column(name = "source_account_number", length = 26)
    private String sourceAccountNumber;

    @Column(name = "authorization_code_hash", length = 64)
    private String authorizationCodeHash;

    @Column(name = "bank_reference", length = 32)
    private String bankReference;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column
    private Instant completedAt;

    @Column
    private Instant redeemedAt;

    protected PaymentAuthorization() {
    }

    public PaymentAuthorization(
            String id,
            String shopOrderId,
            String shopCustomerId,
            String merchantAccountNumber,
            BigDecimal amount,
            String currency,
            String title,
            String paymentIdempotencyKey,
            String state,
            String returnUrl,
            Instant createdAt,
            Instant expiresAt
    ) {
        this.id = id;
        this.shopOrderId = shopOrderId;
        this.shopCustomerId = shopCustomerId;
        this.merchantAccountNumber = merchantAccountNumber;
        this.amount = amount;
        this.currency = currency;
        this.title = title;
        this.paymentIdempotencyKey = paymentIdempotencyKey;
        this.state = state;
        this.returnUrl = returnUrl;
        this.status = AuthorizationStatus.PENDING;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getId() { return id; }
    public String getShopOrderId() { return shopOrderId; }
    public String getShopCustomerId() { return shopCustomerId; }
    public String getMerchantAccountNumber() { return merchantAccountNumber; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getTitle() { return title; }
    public String getPaymentIdempotencyKey() { return paymentIdempotencyKey; }
    public String getState() { return state; }
    public String getReturnUrl() { return returnUrl; }
    public AuthorizationStatus getStatus() { return status; }
    public String getSourceAccountNumber() { return sourceAccountNumber; }
    public String getAuthorizationCodeHash() { return authorizationCodeHash; }
    public String getBankReference() { return bankReference; }
    public String getFailureReason() { return failureReason; }
    public Instant getExpiresAt() { return expiresAt; }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void complete(String sourceAccountNumber, String authorizationCodeHash, String bankReference, Instant completedAt) {
        this.sourceAccountNumber = sourceAccountNumber;
        this.authorizationCodeHash = authorizationCodeHash;
        this.bankReference = bankReference;
        this.completedAt = completedAt;
        this.status = AuthorizationStatus.COMPLETED;
    }

    public void fail(String reason) {
        this.failureReason = reason;
        this.status = AuthorizationStatus.FAILED;
    }

    public void markRedeemed(Instant redeemedAt) {
        this.redeemedAt = redeemedAt;
        this.status = AuthorizationStatus.REDEEMED;
    }
}
