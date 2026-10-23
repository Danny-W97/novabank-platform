package org.demo.bankdemo.card;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.demo.bankdemo.card.CardService.*;
import static org.demo.bankdemo.card.CardPaymentPolicy.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.account.AccountStatus;
import org.demo.bankdemo.currency.CurrencyBalanceRepository;
import org.demo.bankdemo.currency.CurrencyCode;
import org.demo.bankdemo.currency.ExchangeRateRepository;
import org.demo.bankdemo.transaction.BankTransaction;
import org.demo.bankdemo.transaction.TransactionRepository;
import java.util.List;
import java.util.UUID;

/** Online accounting joins the merchant transaction; no REQUIRES_NEW and no swallowed failures. */
@Service
public class OnlineCardPaymentService {
    private final AccountRepository accountRepository;
    private final CurrencyBalanceRepository balanceRepository;
    private final DemoCardRepository cardRepository;
    private final CardTransactionRepository cardTransactionRepository;
    private final Clock clock;
    private final TransactionRepository bankTransactionRepository;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final CardCurrencyValuation valuation;

    public OnlineCardPaymentService(
            AccountRepository accountRepository,
            CurrencyBalanceRepository balanceRepository,
            DemoCardRepository cardRepository,
            CardTransactionRepository cardTransactionRepository,
            Clock clock,
            TransactionRepository bankTransactionRepository,
            ExchangeRateRepository exchangeRateRepository
    ) {
        this.accountRepository = java.util.Objects.requireNonNull(accountRepository);
        this.balanceRepository = java.util.Objects.requireNonNull(balanceRepository);
        this.cardRepository = java.util.Objects.requireNonNull(cardRepository);
        this.cardTransactionRepository = java.util.Objects.requireNonNull(cardTransactionRepository);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.bankTransactionRepository = java.util.Objects.requireNonNull(bankTransactionRepository);
        this.valuation = new CardCurrencyValuation(exchangeRateRepository);
    }

    @Transactional
    public OnlinePaymentResult payOnline(
            String onlineNumber,
            String cvv,
            String expiry,
            String merchantAccountNumber,
            String orderId,
            CurrencyCode currency,
            BigDecimal amountPln,
            BigDecimal amount,
            String title,
            String idempotencyKey,
            String merchantId
    ) {
        validateOnline(onlineNumber, cvv, expiry, merchantAccountNumber, orderId, currency,
                amountPln, amount, title, idempotencyKey);
        if (merchantId == null || !merchantId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new CardException("A stable merchant identity is required.");
        var key = idempotencyKey.strip();
        var existing = cardTransactionRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            var transaction = existing.get();
            if (!transaction.getCard().getOnlineNumber().equals(onlineNumber.strip())
                    || !transaction.getCard().getDemoCvv().equals(cvv.strip())
                    || !java.time.YearMonth.from(transaction.getCard().getDemoExpiresAt())
                            .equals(parseExpiry(expiry))
                    || transaction.getCurrency() != currency
                    || transaction.getAmount().compareTo(amount) != 0
                    || transaction.getAmountInPln().compareTo(amountPln) != 0
                    || !merchantAccountNumber.equals(transaction.getMerchantAccountNumber())
                    || !orderId.equals(transaction.getOrderId())
                    || !title.equals(transaction.getPaymentTitle())) {
                throw new CardException("Klucz operacji zostal juz wykorzystany dla innych danych.");
            }
            var reference = transaction.getBankReference();
            if (reference == null && transaction.getStatus() == CardTransactionStatus.COMPLETED) {
                reference = bankTransactionRepository.findByIdempotencyKey(key)
                        .map(BankTransaction::getReferenceNumber).orElse(null);
            }
            if (transaction.getStatus() == CardTransactionStatus.COMPLETED && reference == null) {
                throw new CardException("Nie można potwierdzić referencji wcześniejszej płatności.");
            }
            return onlineResult(transaction, reference, true);
        }

        var sourceId = cardRepository.findAccountIdByOnlineNumber(onlineNumber.strip())
                .orElseThrow(() -> new CardException("Nie znaleziono karty demonstracyjnej."));
        var destinationId = accountRepository.findIdByAccountNumber(merchantAccountNumber)
                .orElseThrow(() -> new CardException("Nie znaleziono rachunku sklepu."));
        if (sourceId.equals(destinationId)) {
            throw new CardException("Rachunek klienta i sklepu musza byc rozne.");
        }
        var accountIds = List.of(sourceId, destinationId)
                .stream().sorted().toList();
        var lockedAccounts = accountRepository.findAllByIdForUpdate(accountIds);
        if (lockedAccounts.size() != 2) {
            throw new CardException("Nie mozna zablokowac rachunkow do platnosci.");
        }
        // A concurrent retry can commit while this request waits for the account locks.
        if (cardTransactionRepository.findByIdempotencyKey(key).isPresent()) {
            return payOnline(onlineNumber, cvv, expiry, merchantAccountNumber, orderId,
                    currency, amountPln, amount, title, idempotencyKey, merchantId);
        }
        lockedAccounts.forEach(account -> entityManager.refresh(account, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE));
        var source = accountById(lockedAccounts, sourceId);
        var destination = accountById(lockedAccounts, destinationId);
        var card = cardRepository.findByAccountId(source.getId())
                .orElseThrow(() -> new CardException("Nie znaleziono karty demonstracyjnej."));
        entityManager.refresh(card, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (!card.getDemoCvv().equals(cvv.strip())) {
            throw new CardException("Nieprawidlowy kod CVV.");
        }
        if (!java.time.YearMonth.from(card.getDemoExpiresAt()).equals(parseExpiry(expiry))) {
            throw new CardException("Nieprawidlowa data waznosci karty.");
        }
        if (java.time.YearMonth.now(clock).isAfter(java.time.YearMonth.from(card.getDemoExpiresAt()))) {
            throw new CardException("Karta jest przeterminowana.");
        }
        if (card.statusAt(Instant.now(clock)) != CardStatus.ACTIVE) {
            throw new CardException("Karta jest zablokowana.");
        }
        if (source.getStatus() != AccountStatus.ACTIVE || destination.getStatus() != AccountStatus.ACTIVE) {
            throw new CardException("Jeden z rachunkow nie jest aktywny.");
        }
        if (!"PLN".equals(destination.getCurrency())) {
            throw new CardException("Rachunek sklepu musi byc prowadzony w PLN.");
        }

        var expectedAmount = valuation.fromPln(currency, amountPln);
        if (expectedAmount.compareTo(amount) != 0) {
            throw new CardException("Kurs platnosci wygasl. Pobierz nowe przeliczenie.");
        }
        var amountInPln = amountPln.setScale(2);
        var now = Instant.now(clock);
        var dayStart = now.atZone(BANK_ZONE).toLocalDate().atStartOfDay(BANK_ZONE).toInstant();
        var dayEnd = dayStart.atZone(BANK_ZONE).plusDays(1).toInstant();
        var dailySpent = nonNull(cardTransactionRepository.sumCompletedAmountInPln(card.getId(), dayStart, dayEnd));
        var reason = dailySpent.add(amountInPln).compareTo(limit(card.getDailyLimit())) > 0
                ? "Przekroczono dzienny limit karty."
                : null;
        if (reason == null) {
            var foreign = currency == CurrencyCode.PLN ? null : balanceRepository
                    .findByAccountIdAndCurrencyForUpdate(source.getId(), currency)
                    .orElseThrow(() -> new CardException("Brak subkonta w wybranej walucie."));
            var available = foreign == null ? source.getBalance() : foreign.getBalance();
            if (available.compareTo(amount) < 0) reason = "Brak wystarczajacych srodkow w wybranej walucie.";
            else {
                if (foreign == null) source.debit(amount); else foreign.debit(amount);
                // Unexpected failures after the debit must escape and roll back, never become a committed DECLINED.
                destination.credit(amountInPln);
            }
        }

        var cardTransaction = new CardTransaction(
                card,
                "NovaTech Shop",
                currency,
                amount.setScale(2),
                amountInPln,
                key,
                CardPaymentChannel.ONLINE,
                reason == null ? CardTransactionStatus.COMPLETED : CardTransactionStatus.DECLINED,
                reason
        );
        cardTransaction.setOnlinePaymentDetails(merchantAccountNumber, orderId, title);
        cardTransaction = cardTransactionRepository.saveAndFlush(cardTransaction);
        String reference = null;
        if (reason == null) {
            reference = "CARD" + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
            var financialTransaction = BankTransaction.payment(
                    reference,
                    key,
                    orderId,
                    source,
                    destination,
                    source.getOwner().getFullName(),
                    destination.getOwner().getFullName(),
                    amountInPln,
                    title,
                    now
            );
            financialTransaction.assignMerchant(merchantId);
            bankTransactionRepository.saveAndFlush(financialTransaction);
            cardTransaction.setBankReference(reference);
        }
        return new OnlinePaymentResult(
                cardTransaction.getStatus(), amount, currency, amountInPln, reason, reference,
                source.getAccountNumber(), false
        );
    }
}
