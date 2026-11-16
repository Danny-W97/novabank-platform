package org.demo.bankdemo.card;

import org.springframework.stereotype.Service;
import org.demo.bankdemo.currency.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Stable facade. Transactional work is delegated to proxied services; DTO contracts and caller transactions are preserved. */
@Service
public class CardService {
    public static final BigDecimal DEFAULT_DAILY_LIMIT = CardPaymentPolicy.DEFAULT_DAILY_LIMIT;
    private final CardQueryService queries;
    private final CardLifecycleService lifecycle;
    private final CardPaymentService payments;
    private final OnlineCardPaymentService onlinePayments;

    public CardService(
            CardQueryService queries,
            CardLifecycleService lifecycle,
            CardPaymentService payments,
            OnlineCardPaymentService onlinePayments
    ) {
        this.queries = java.util.Objects.requireNonNull(queries);
        this.lifecycle = java.util.Objects.requireNonNull(lifecycle);
        this.payments = java.util.Objects.requireNonNull(payments);
        this.onlinePayments = java.util.Objects.requireNonNull(onlinePayments);
    }

    public CardView cardFor(String email) { return queries.cardFor(email); }

    public BigDecimal dailyLimitFor(String email) { return queries.dailyLimitFor(email); }

    public void updateDailyLimit(String email, BigDecimal limit) { lifecycle.updateDailyLimit(email, limit); }

    public CardView attach(String email) { return lifecycle.attach(email); }

    public void block(String email) { lifecycle.block(email); }

    public void blockTemporarily(String email, int minutes) { lifecycle.blockTemporarily(email, minutes); }

    public void activate(String email) { lifecycle.activate(email); }

    public TransactionResult pay(String email, String merchantName, CurrencyCode currency, BigDecimal amount, String key) {
        return payments.pay(email, merchantName, currency, amount, CardPaymentChannel.IN_STORE, key);
    }

    public TransactionResult pay(String email, String merchantName, CurrencyCode currency, BigDecimal amount, CardPaymentChannel channel, String key) {
        return payments.pay(email, merchantName, currency, amount, channel, key);
    }

    public OnlinePaymentResult payOnline(String number, String cvv, String expiry, String merchantAccount, String order,
            CurrencyCode currency, BigDecimal amountPln, BigDecimal amount, String title, String key, String merchantId) {
        return onlinePayments.payOnline(number, cvv, expiry, merchantAccount, order, currency, amountPln, amount, title, key, merchantId);
    }

    public OnlinePaymentResult onlinePaymentStatus(String key, String merchantAccount, String order) {
        return queries.onlinePaymentStatus(key, merchantAccount, order);
    }

    public record CardView(
            String maskedNumber,
            String onlineNumber,
            String demoCvv,
            java.time.LocalDate demoExpiresAt,
            CardStatus status,
            Instant blockedUntil,
            BigDecimal dailyLimit,
            BigDecimal dailySpent,
            BigDecimal dailyRemaining,
            List<TransactionView> transactions
    ) {
    }

    public record TransactionView(
            CurrencyCode currency,
            String merchantName,
            BigDecimal amount,
            CardTransactionStatus status,
            String reason,
            CardPaymentChannel channel,
            String createdAt
    ) {
    }

    public record TransactionResult(
            CurrencyCode currency,
            BigDecimal amount,
            CardTransactionStatus status,
            String reason,
            CardPaymentChannel channel
    ) {
    }

    public record OnlinePaymentResult(
            CardTransactionStatus status,
            BigDecimal amount,
            CurrencyCode currency,
            BigDecimal amountInPln,
            String reason,
            String referenceNumber,
            String sourceAccountNumber,
            boolean replayed
    ) {
    }
}
