package org.demo.bankdemo.card;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.demo.bankdemo.card.CardService.*;
import static org.demo.bankdemo.card.CardPaymentPolicy.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.transaction.TransactionRepository;
import org.demo.bankdemo.transaction.BankTransaction;
import org.springframework.data.domain.PageRequest;

@Service
public class CardQueryService {
    private final AccountRepository accountRepository;
    private final DemoCardRepository cardRepository;
    private final CardTransactionRepository cardTransactionRepository;
    private final TransactionRepository bankTransactionRepository;
    private final Clock clock;

    public CardQueryService(
            AccountRepository accountRepository,
            DemoCardRepository cardRepository,
            CardTransactionRepository cardTransactionRepository,
            TransactionRepository bankTransactionRepository,
            Clock clock
    ) {
        this.accountRepository = java.util.Objects.requireNonNull(accountRepository);
        this.cardRepository = java.util.Objects.requireNonNull(cardRepository);
        this.cardTransactionRepository = java.util.Objects.requireNonNull(cardTransactionRepository);
        this.bankTransactionRepository = java.util.Objects.requireNonNull(bankTransactionRepository);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    @Transactional
    public CardView cardFor(String email) {
        var account = accountRepository.findByOwnerEmail(normalize(email))
                .orElseThrow(() -> new CardException("Zalogowany uzytkownik nie posiada rachunku."));
        var card = cardRepository.findByAccountId(account.getId()).orElse(null);
        if (card == null) {
            return null;
        }

        var now = Instant.now(clock);
        var status = card.statusAt(now);
        var dayStart = now.atZone(BANK_ZONE).toLocalDate().atStartOfDay(BANK_ZONE).toInstant();
        var dayEnd = dayStart.atZone(BANK_ZONE).plusDays(1).toInstant();
        var dailyLimit = limit(card.getDailyLimit());
        var dailySpent = nonNull(cardTransactionRepository.sumCompletedAmountInPln(card.getId(), dayStart, dayEnd));
        var dailyRemaining = dailyLimit.subtract(dailySpent).max(BigDecimal.ZERO).setScale(2);
        var transactions = cardTransactionRepository.findByCardIdOrderByCreatedAtDesc(
                        card.getId(), PageRequest.of(0, 20))
                .stream()
                .map(transaction -> new TransactionView(
                        transaction.getCurrency(),
                        transaction.getMerchantName(),
                        transaction.getAmount(),
                        transaction.getStatus(),
                        transaction.getReason(),
                        transaction.getChannel(),
                        DATE_TIME.format(transaction.getCreatedAt())
                ))
                .toList();
        return new CardView(card.getMaskedNumber(), card.getOnlineNumber(), card.getDemoCvv(), card.getDemoExpiresAt(), status, card.getBlockedUntil(),
                dailyLimit, dailySpent, dailyRemaining, transactions);
    }

    @Transactional(readOnly = true)
    public BigDecimal dailyLimitFor(String email) {
        var account = accountRepository.findByOwnerEmail(normalize(email))
                .orElseThrow(() -> new CardException("Zalogowany uzytkownik nie posiada rachunku."));
        return cardRepository.findByAccountId(account.getId())
                .map(card -> limit(card.getDailyLimit()))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public OnlinePaymentResult onlinePaymentStatus(String idempotencyKey,
                                                   String merchantAccountNumber, String orderId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || merchantAccountNumber == null || orderId == null) {
            throw new CardException("Brak danych identyfikujących płatność kartą.");
        }
        var transaction = cardTransactionRepository.findByIdempotencyKey(idempotencyKey.strip())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Płatność kartą nie została jeszcze zapisana."));
        if (transaction.getChannel() != CardPaymentChannel.ONLINE
                || !merchantAccountNumber.equals(transaction.getMerchantAccountNumber())
                || !orderId.equals(transaction.getOrderId())) {
            throw new CardException("Płatność kartą nie pasuje do zamówienia.");
        }
        var reference = transaction.getBankReference();
        if (reference == null && transaction.getStatus() == CardTransactionStatus.COMPLETED) {
            reference = bankTransactionRepository.findByIdempotencyKey(idempotencyKey.strip())
                    .map(BankTransaction::getReferenceNumber).orElse(null);
        }
        if (transaction.getStatus() == CardTransactionStatus.COMPLETED && reference == null) {
            throw new CardException("Nie można potwierdzić referencji płatności kartą.");
        }
        return onlineResult(transaction, reference, true);
    }
}
