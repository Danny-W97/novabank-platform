package org.demo.bankdemo.payment.authorization;

import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.config.PaymentAuthorizationProperties;
import org.demo.bankdemo.payment.PaymentException;
import org.demo.bankdemo.payment.PaymentRequest;
import org.demo.bankdemo.payment.PaymentResponse;
import org.demo.bankdemo.payment.PaymentService;
import org.demo.bankdemo.payment.MerchantIdentity;
import org.demo.bankdemo.payment.PaymentPurchaseGuard;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class PaymentAuthorizationService {

    private final PaymentAuthorizationRepository repository;
    private final AccountRepository accountRepository;
    private final PaymentService paymentService;
    private final PaymentAuthorizationProperties properties;
    private final Clock clock;
    private final PaymentPurchaseGuard purchases;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;

    public PaymentAuthorizationService(
            PaymentAuthorizationRepository repository,
            AccountRepository accountRepository,
            PaymentService paymentService,
            PaymentAuthorizationProperties properties,
            Clock clock,
            PaymentPurchaseGuard purchases,
            PlatformTransactionManager transactionManager,
            EntityManager entityManager
    ) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.paymentService = paymentService;
        this.properties = properties;
        this.clock = clock;
        this.purchases = purchases;
        this.transactions = new TransactionTemplate(transactionManager);
        this.entityManager = entityManager;
    }

    @Transactional
    public PaymentAuthorizationResponse create(MerchantIdentity merchant, PaymentAuthorizationRequest request) {
        var existing = repository.findByPaymentIdempotencyKey(request.paymentIdempotencyKey());
        if (existing.isPresent()) {
            var authorization = existing.get();
            merchant.requireOwner(authorization.getMerchantId());
            validateSameRequest(authorization, request);
            return response(authorization);
        }

        purchases.begin(merchant, request.shopOrderId(), request.amount(), request.currency(),
                request.merchantAccountNumber(), request.paymentIdempotencyKey(), "BANK");
        // Recheck after the purchase lock: another request may have created the same authorization.
        existing = repository.findByPaymentIdempotencyKey(request.paymentIdempotencyKey());
        if (existing.isPresent()) {
            merchant.requireOwner(existing.get().getMerchantId());
            validateSameRequest(existing.get(), request);
            return response(existing.get());
        }
        var now = Instant.now(clock);
        var authorization = new PaymentAuthorization(
                UUID.randomUUID().toString().replace("-", ""),
                request.shopOrderId(),
                request.shopCustomerId(),
                request.merchantAccountNumber(),
                request.amount(),
                request.currency(),
                request.title(),
                request.paymentIdempotencyKey(),
                request.state(),
                properties.getShopCallbackUrl(),
                now,
                now.plus(Math.max(1, properties.getExpiryMinutes()), ChronoUnit.MINUTES)
        );
        authorization.assignMerchant(merchant.id());
        repository.save(authorization);
        return response(authorization);
    }

    @Transactional(readOnly = true)
    public PaymentAuthorization findForDisplay(String authorizationId) {
        return find(authorizationId);
    }

    @Transactional
    public PaymentAuthorizationStatusResponse status(MerchantIdentity merchant, String authorizationId) {
        merchant.requireOwner(find(authorizationId).getMerchantId());
        var authorization = lockPurchaseAndAuthorization(authorizationId);
        if (authorization.getStatus() == AuthorizationStatus.PENDING
                && authorization.isExpired(Instant.now(clock))) {
            authorization.fail("Authorization expired.");
            repository.save(authorization);
            purchases.finish(authorization.getMerchantId(), authorization.getShopOrderId(),
                    authorization.getPaymentIdempotencyKey(), null);
        }
        return PaymentAuthorizationStatusResponse.from(authorization);
    }

    @Transactional
    public PaymentAuthorizationStatusResponse statusByKey(MerchantIdentity merchant, String key) {
        var authorization = repository.findByPaymentIdempotencyKey(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Authorization not found."));
        return status(merchant, authorization.getId());
    }

    /** Same purchase/authorization locks as approve: a lost response or missing key is never a refusal. */
    @Transactional
    public PaymentAuthorizationStatusResponse cancelByKey(MerchantIdentity merchant,String key) {
        var snapshot=repository.findByPaymentIdempotencyKey(key)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Authorization not found."));
        merchant.requireOwner(snapshot.getMerchantId());
        var authorization=lockPurchaseAndAuthorization(snapshot.getId());
        if(authorization.getStatus()==AuthorizationStatus.PENDING){
            authorization.fail("Purchase cancellation confirmed by merchant.");
            purchases.finish(authorization.getMerchantId(),authorization.getShopOrderId(),authorization.getPaymentIdempotencyKey(),null);
            repository.saveAndFlush(authorization);
        }
        return PaymentAuthorizationStatusResponse.from(authorization);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String approve(String authorizationId, String bankUserEmail) {
        try {
            return transactions.execute(status -> approveInTransaction(authorizationId, bankUserEmail));
        } catch (PaymentException exception) {
            // This transaction starts only after the failed approval transaction released its locks.
            return transactions.execute(status -> {
                var authorization = lockPurchaseAndAuthorization(authorizationId);
                if (authorization.getStatus() != AuthorizationStatus.PENDING)
                    return callback(authorization, authorization.getStatus().name().toLowerCase(), null);
                authorization.fail("Payment declined.");
                purchases.finish(authorization.getMerchantId(), authorization.getShopOrderId(),
                        authorization.getPaymentIdempotencyKey(), null);
                repository.saveAndFlush(authorization);
                return callback(authorization, "failed", null, "payment_declined");
            });
        }
        // Infrastructure/commit errors intentionally escape: their result must be reconciled, not declared FAILED.
    }

    private String approveInTransaction(String authorizationId, String bankUserEmail) {
        var authorization = lockPurchaseAndAuthorization(authorizationId);
        if (authorization.getStatus() != AuthorizationStatus.PENDING) {
            return callback(authorization, authorization.getStatus().name().toLowerCase(), null);
        }
        if (authorization.isExpired(Instant.now(clock))) {
            authorization.fail("Authorization expired.");
            repository.save(authorization);
            purchases.finish(authorization.getMerchantId(), authorization.getShopOrderId(),
                    authorization.getPaymentIdempotencyKey(), null);
            return callback(authorization, "expired", null);
        }

        PaymentResponse payment;
        String sourceAccountNumber;
            sourceAccountNumber = accountRepository.findAccountNumberByOwnerEmail(bankUserEmail)
                    .orElseThrow(() -> new PaymentException("Nie znaleziono rachunku klienta."));
            payment = paymentService.execute(
                    authorization.getMerchantId(),
                    authorization.getPaymentIdempotencyKey(),
                    new PaymentRequest(
                            sourceAccountNumber,
                            authorization.getMerchantAccountNumber(),
                            authorization.getShopOrderId(),
                            authorization.getAmount(),
                            authorization.getCurrency(),
                            authorization.getTitle()
                    )
            );

        var rawCode = UUID.randomUUID().toString() + UUID.randomUUID();
        authorization.complete(
                sourceAccountNumber,
                hash(rawCode),
                payment.referenceNumber(),
                Instant.now(clock)
        );
        repository.save(authorization);
        purchases.finish(authorization.getMerchantId(), authorization.getShopOrderId(),
                authorization.getPaymentIdempotencyKey(), payment.referenceNumber());
        return callback(authorization, "approved", rawCode);
    }

    @Transactional
    public String reject(String authorizationId) {
        var authorization = lockPurchaseAndAuthorization(authorizationId);
        if (authorization.getStatus() == AuthorizationStatus.PENDING) {
            authorization.fail("Payment was cancelled by the customer.");
            repository.save(authorization);
            purchases.finish(authorization.getMerchantId(), authorization.getShopOrderId(),
                    authorization.getPaymentIdempotencyKey(), null);
            return callback(authorization, "cancelled", null);
        }
        return callback(authorization, authorization.getStatus().name().toLowerCase(), null);
    }

    @Transactional
    public PaymentAuthorizationRedeemResponse redeem(
            MerchantIdentity merchant,
            String authorizationId,
            PaymentAuthorizationRedeemRequest request
    ) {
        merchant.requireOwner(find(authorizationId).getMerchantId());
        var authorization = lockPurchaseAndAuthorization(authorizationId);
        if (!MessageDigest.isEqual(
                authorization.getState().getBytes(StandardCharsets.UTF_8),
                request.state().getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid authorization state.");
        }
        if (authorization.getStatus() != AuthorizationStatus.COMPLETED
                || authorization.getAuthorizationCodeHash() == null
                || !MessageDigest.isEqual(
                        authorization.getAuthorizationCodeHash().getBytes(StandardCharsets.UTF_8),
                        hash(request.code()).getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Authorization code is invalid or already used.");
        }
        authorization.markRedeemed(Instant.now(clock));
        repository.save(authorization);
        return new PaymentAuthorizationRedeemResponse(
                authorization.getShopOrderId(),
                authorization.getAmount(),
                authorization.getCurrency(),
                "COMPLETED",
                authorization.getBankReference(),
                authorization.getSourceAccountNumber()
        );
    }

    private PaymentAuthorization find(String authorizationId) {
        return repository.findById(authorizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Authorization not found."));
    }

    private PaymentAuthorization findForUpdate(String authorizationId) {
        var authorization = repository.findByIdForUpdate(authorizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Authorization not found."));
        entityManager.refresh(authorization, LockModeType.PESSIMISTIC_WRITE);
        return authorization;
    }

    private PaymentAuthorization lockPurchaseAndAuthorization(String authorizationId) {
        var snapshot = find(authorizationId);
        purchases.lock(snapshot.getMerchantId(), snapshot.getShopOrderId());
        return findForUpdate(authorizationId);
    }

    private PaymentAuthorizationResponse response(PaymentAuthorization authorization) {
        return new PaymentAuthorizationResponse(
                authorization.getId(),
                properties.getBankBaseUrl() + "/shop/authorize/" + authorization.getId(),
                authorization.getState(),
                authorization.getExpiresAt(),
                authorization.getMerchantId()
        );
    }

    private String callback(PaymentAuthorization authorization, String status, String code) {
        return callback(authorization, status, code, null);
    }

    private String callback(
            PaymentAuthorization authorization,
            String status,
            String code,
            String reason
    ) {
        var builder = UriComponentsBuilder.fromUriString(authorization.getReturnUrl())
                .queryParam("authorizationId", authorization.getId())
                .queryParam("state", authorization.getState())
                .queryParam("status", status);
        if (code != null) {
            builder.queryParam("code", code);
        }
        if (reason != null) {
            builder.queryParam("reason", reason);
        }
        return builder.build().encode().toUriString();
    }



    private static void validateSameRequest(
            PaymentAuthorization existing,
            PaymentAuthorizationRequest request
    ) {
        if (!existing.getShopOrderId().equals(request.shopOrderId())
                || !existing.getShopCustomerId().equals(request.shopCustomerId())
                || !existing.getTitle().equals(request.title())
                || existing.getAmount().compareTo(request.amount()) != 0
                || !existing.getCurrency().equals(request.currency())
                || !existing.getMerchantAccountNumber().equals(request.merchantAccountNumber())
                || !existing.getState().equals(request.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key is already used for different payment data.");
        }
    }

    private static String hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            var result = new StringBuilder(bytes.length * 2);
            for (byte byteValue : bytes) {
                result.append(String.format("%02x", byteValue));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
