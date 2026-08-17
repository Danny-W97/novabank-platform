package org.demo.bankdemo.payment.authorization;

import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.config.PaymentAuthorizationProperties;
import org.demo.bankdemo.payment.PaymentException;
import org.demo.bankdemo.payment.PaymentRequest;
import org.demo.bankdemo.payment.PaymentResponse;
import org.demo.bankdemo.payment.PaymentService;
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

    public PaymentAuthorizationService(
            PaymentAuthorizationRepository repository,
            AccountRepository accountRepository,
            PaymentService paymentService,
            PaymentAuthorizationProperties properties,
            Clock clock
    ) {
        this.repository = repository;
        this.accountRepository = accountRepository;
        this.paymentService = paymentService;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public PaymentAuthorizationResponse create(PaymentAuthorizationRequest request) {
        var existing = repository.findByPaymentIdempotencyKey(request.paymentIdempotencyKey());
        if (existing.isPresent()) {
            var authorization = existing.get();
            validateSameRequest(authorization, request);
            return response(authorization);
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
        repository.save(authorization);
        return response(authorization);
    }

    @Transactional(readOnly = true)
    public PaymentAuthorization findForDisplay(String authorizationId) {
        return find(authorizationId);
    }

    @Transactional
    public PaymentAuthorizationStatusResponse status(String authorizationId) {
        var authorization = findForUpdate(authorizationId);
        if (authorization.getStatus() == AuthorizationStatus.PENDING
                && authorization.isExpired(Instant.now(clock))) {
            authorization.fail("Authorization expired.");
            repository.save(authorization);
        }
        return PaymentAuthorizationStatusResponse.from(authorization);
    }

    @Transactional
    public String approve(String authorizationId, String bankUserEmail) {
        var authorization = findForUpdate(authorizationId);
        if (authorization.isExpired(Instant.now(clock))) {
            authorization.fail("Authorization expired.");
            repository.save(authorization);
            return callback(authorization, "expired", null);
        }
        if (authorization.getStatus() != AuthorizationStatus.PENDING) {
            return callback(authorization, authorization.getStatus().name().toLowerCase(), null);
        }

        PaymentResponse payment;
        String sourceAccountNumber;
        try {
            var source = accountRepository.findByOwnerEmail(bankUserEmail)
                    .orElseThrow(() -> new PaymentException("Nie znaleziono rachunku klienta."));
            sourceAccountNumber = source.getAccountNumber();
            payment = paymentService.execute(
                    authorization.getPaymentIdempotencyKey(),
                    new PaymentRequest(
                            source.getAccountNumber(),
                            authorization.getMerchantAccountNumber(),
                            authorization.getShopOrderId(),
                            authorization.getAmount(),
                            authorization.getCurrency(),
                            authorization.getTitle()
                    )
            );
        } catch (PaymentException | IllegalStateException | IllegalArgumentException exception) {
            authorization.fail(exception.getMessage());
            repository.save(authorization);
            return callback(authorization, "failed", null, failureCode(exception.getMessage()));
        }

        var rawCode = UUID.randomUUID().toString() + UUID.randomUUID();
        authorization.complete(
                sourceAccountNumber,
                hash(rawCode),
                payment.referenceNumber(),
                Instant.now(clock)
        );
        repository.save(authorization);
        return callback(authorization, "approved", rawCode);
    }

    @Transactional
    public String reject(String authorizationId) {
        var authorization = findForUpdate(authorizationId);
        if (authorization.getStatus() == AuthorizationStatus.PENDING) {
            authorization.fail("Payment was cancelled by the customer.");
            repository.save(authorization);
        }
        return callback(authorization, "cancelled", null);
    }

    @Transactional
    public PaymentAuthorizationRedeemResponse redeem(
            String authorizationId,
            PaymentAuthorizationRedeemRequest request
    ) {
        var authorization = findForUpdate(authorizationId);
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
        return repository.findByIdForUpdate(authorizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Authorization not found."));
    }

    private PaymentAuthorizationResponse response(PaymentAuthorization authorization) {
        return new PaymentAuthorizationResponse(
                authorization.getId(),
                properties.getBankBaseUrl() + "/shop/authorize/" + authorization.getId(),
                authorization.getState(),
                authorization.getExpiresAt()
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

    private static String failureCode(String message) {
        if (message != null && message.toLowerCase().contains("srodk")) {
            return "insufficient_funds";
        }
        return "payment_failed";
    }

    private static void validateSameRequest(
            PaymentAuthorization existing,
            PaymentAuthorizationRequest request
    ) {
        if (!existing.getShopOrderId().equals(request.shopOrderId())
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
