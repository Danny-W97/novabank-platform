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

/** In-store accounting; the account lock, debit and card result stay in one transaction. */
@Service
public class CardPaymentService {
    private final AccountRepository accountRepository;
    private final CurrencyBalanceRepository balanceRepository;
    private final DemoCardRepository cardRepository;
    private final CardTransactionRepository cardTransactionRepository;
    private final Clock clock;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;
    private final CardCurrencyValuation valuation;

    public CardPaymentService(
            AccountRepository accountRepository,
            CurrencyBalanceRepository balanceRepository,
            DemoCardRepository cardRepository,
            CardTransactionRepository cardTransactionRepository,
            Clock clock,
            ExchangeRateRepository exchangeRateRepository
    ) {
        this.accountRepository = java.util.Objects.requireNonNull(accountRepository);
        this.balanceRepository = java.util.Objects.requireNonNull(balanceRepository);
        this.cardRepository = java.util.Objects.requireNonNull(cardRepository);
        this.cardTransactionRepository = java.util.Objects.requireNonNull(cardTransactionRepository);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.valuation = new CardCurrencyValuation(exchangeRateRepository);
    }

    @Transactional
    public TransactionResult pay(
            String email,
            String merchantName,
            CurrencyCode currency,
            BigDecimal amount,
            CardPaymentChannel channel,
            String idempotencyKey
    ) {
        validate(merchantName, currency, channel, amount, idempotencyKey);
        var key = idempotencyKey.strip();
        var normalizedEmail = normalize(email);
        var existing = cardTransactionRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            var transaction = existing.get();
            if (!transaction.getCard().getAccount().getOwner().getEmail().equals(normalizedEmail)
                    || !transaction.getMerchantName().equals(merchantName.strip())
                    || transaction.getCurrency() != currency
                    || transaction.getChannel() != channel
                    || transaction.getAmount().compareTo(amount) != 0) {
                throw new CardException("Klucz operacji zostal juz wykorzystany.");
            }
            return result(transaction);
        }

        var account = lockedAccount(email);
        existing = cardTransactionRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            // A concurrent request may have committed while this request waited for the account.
            return pay(email, merchantName, currency, amount, channel, key);
        }
        entityManager.refresh(account, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        var card = cardRepository.findByAccountId(account.getId())
                .orElseThrow(() -> new CardException("Najpierw podlacz karte do rachunku."));
        entityManager.refresh(card, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (card.statusAt(Instant.now(clock)) != CardStatus.ACTIVE) {
            throw new CardException("Karta jest zablokowana.");
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new CardException("Rachunek nie jest aktywny.");
        }

        var amountInPln = amountInPln(currency, amount);
        var now = Instant.now(clock);
        var dayStart = now.atZone(BANK_ZONE).toLocalDate().atStartOfDay(BANK_ZONE).toInstant();
        var dayEnd = dayStart.atZone(BANK_ZONE).plusDays(1).toInstant();
        var dailySpent = nonNull(cardTransactionRepository.sumCompletedAmountInPln(card.getId(), dayStart, dayEnd));
        var dailyLimit = limit(card.getDailyLimit());
        String reason = dailySpent.add(amountInPln).compareTo(dailyLimit) > 0
                ? "Przekroczono dzienny limit karty."
                : null;

        if (reason == null) {
            try {
                if (currency == CurrencyCode.PLN) {
                    account.debit(amount);
                } else {
                    var balance = balanceRepository.findByAccountIdAndCurrencyForUpdate(account.getId(), currency)
                            .orElseThrow(() -> new CardException("Brak subkonta w wybranej walucie."));
                    balance.debit(amount);
                }
            } catch (IllegalStateException exception) {
                reason = "Brak wystarczajacych srodkow na subkoncie.";
            }
        }

        var transaction = cardTransactionRepository.saveAndFlush(new CardTransaction(
                card,
                merchantName.strip(),
                currency,
                amount.setScale(2),
                amountInPln,
                key,
                channel,
                reason == null ? CardTransactionStatus.COMPLETED : CardTransactionStatus.DECLINED,
                reason
        ));
        return result(transaction);
    }

    private Account lockedAccount(String email) {
        return accountRepository.findByOwnerEmailForUpdate(normalize(email))
                .orElseThrow(() -> new CardException("Zalogowany uzytkownik nie posiada rachunku."));
    }

    private BigDecimal amountInPln(CurrencyCode currency, BigDecimal amount) {
        return valuation.inPln(currency, amount);
    }
}
