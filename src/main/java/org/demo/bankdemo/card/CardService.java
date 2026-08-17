package org.demo.bankdemo.card;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.account.AccountStatus;
import org.demo.bankdemo.currency.CurrencyBalanceRepository;
import org.demo.bankdemo.currency.CurrencyCode;
import org.demo.bankdemo.currency.ExchangeRateRepository;
import org.demo.bankdemo.transaction.BankTransaction;
import org.demo.bankdemo.transaction.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class CardService {

    private static final ZoneId BANK_ZONE = ZoneId.of("Europe/Warsaw");
    public static final BigDecimal DEFAULT_DAILY_LIMIT = new BigDecimal("5000.00");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.forLanguageTag("pl-PL"))
                    .withZone(BANK_ZONE);

    private final AccountRepository accountRepository;
    private final CurrencyBalanceRepository balanceRepository;
    private final DemoCardRepository cardRepository;
    private final CardTransactionRepository cardTransactionRepository;
    private final ExchangeRateRepository exchangeRateRepository;
    private final TransactionRepository bankTransactionRepository;
    private final Clock clock;

    public CardService(
            AccountRepository accountRepository,
            CurrencyBalanceRepository balanceRepository,
            DemoCardRepository cardRepository,
            CardTransactionRepository transactionRepository
    ) {
        this(accountRepository, balanceRepository, cardRepository, transactionRepository,
                null, Clock.systemUTC(), null);
    }

    @Autowired
    public CardService(
            AccountRepository accountRepository,
            CurrencyBalanceRepository balanceRepository,
            DemoCardRepository cardRepository,
            CardTransactionRepository transactionRepository,
            ExchangeRateRepository exchangeRateRepository,
            Clock clock,
            TransactionRepository bankTransactionRepository
    ) {
        this.accountRepository = accountRepository;
        this.balanceRepository = balanceRepository;
        this.cardRepository = cardRepository;
        this.cardTransactionRepository = transactionRepository;
        this.exchangeRateRepository = exchangeRateRepository;
        this.bankTransactionRepository = bankTransactionRepository;
        this.clock = clock;
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
        var dayEnd = dayStart.plus(Duration.ofDays(1));
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

    @Transactional
    public void updateDailyLimit(String email, BigDecimal dailyLimit) {
        var account = lockedAccount(email);
        var card = cardRepository.findByAccountId(account.getId())
                .orElseThrow(() -> new CardException("Najpierw podlacz karte do rachunku."));
        card.updateDailyLimit(dailyLimit);
    }

    @Transactional
    public CardView attach(String email) {
        var account = lockedAccount(email);
        var existing = cardRepository.findByAccountId(account.getId());
        if (existing.isPresent()) {
            var card = existing.get();
            return new CardView(card.getMaskedNumber(), card.getOnlineNumber(), card.getDemoCvv(), card.getDemoExpiresAt(), card.getStatus(), card.getBlockedUntil(),
                    limit(card.getDailyLimit()), BigDecimal.ZERO.setScale(2), limit(card.getDailyLimit()), List.of());
        }
        var card = cardRepository.save(new DemoCard(account, maskedNumber()));
        return new CardView(card.getMaskedNumber(), card.getOnlineNumber(), card.getDemoCvv(), card.getDemoExpiresAt(), card.getStatus(), card.getBlockedUntil(),
                limit(card.getDailyLimit()), BigDecimal.ZERO.setScale(2), limit(card.getDailyLimit()), List.of());
    }

    @Transactional
    public TransactionResult pay(
            String email,
            String merchantName,
            CurrencyCode currency,
            BigDecimal amount,
            String idempotencyKey
    ) {
        return pay(email, merchantName, currency, amount, CardPaymentChannel.IN_STORE, idempotencyKey);
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
        var card = cardRepository.findByAccountId(account.getId())
                .orElseThrow(() -> new CardException("Najpierw podlacz karte do rachunku."));
        if (card.statusAt(Instant.now(clock)) != CardStatus.ACTIVE) {
            throw new CardException("Karta jest zablokowana.");
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new CardException("Rachunek nie jest aktywny.");
        }

        var amountInPln = amountInPln(currency, amount);
        var now = Instant.now(clock);
        var dayStart = now.atZone(BANK_ZONE).toLocalDate().atStartOfDay(BANK_ZONE).toInstant();
        var dayEnd = dayStart.plus(Duration.ofDays(1));
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
            String idempotencyKey
    ) {
        validateOnline(onlineNumber, cvv, expiry, merchantAccountNumber, orderId, currency,
                amountPln, amount, title, idempotencyKey);
        var key = idempotencyKey.strip();
        var existing = cardTransactionRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            var transaction = existing.get();
            if (!transaction.getCard().getOnlineNumber().equals(onlineNumber.strip())
                    || transaction.getCurrency() != currency
                    || transaction.getAmount().compareTo(amount) != 0) {
                throw new CardException("Klucz operacji zostal juz wykorzystany dla innych danych.");
            }
            return onlineResult(transaction, null, true);
        }

        var card = cardRepository.findByOnlineNumber(onlineNumber.strip())
                .orElseThrow(() -> new CardException("Nie znaleziono karty demonstracyjnej."));
        var sourceCandidate = card.getAccount();
        var destinationCandidate = accountRepository.findByAccountNumber(merchantAccountNumber)
                .orElseThrow(() -> new CardException("Nie znaleziono rachunku sklepu."));
        if (sourceCandidate.getId().equals(destinationCandidate.getId())) {
            throw new CardException("Rachunek klienta i sklepu musza byc rozne.");
        }
        var accountIds = List.of(sourceCandidate.getId(), destinationCandidate.getId())
                .stream().sorted().toList();
        var lockedAccounts = accountRepository.findAllByIdForUpdate(accountIds);
        if (lockedAccounts.size() != 2) {
            throw new CardException("Nie mozna zablokowac rachunkow do platnosci.");
        }
        var source = accountById(lockedAccounts, sourceCandidate.getId());
        var destination = accountById(lockedAccounts, destinationCandidate.getId());
        card = cardRepository.findByAccountId(source.getId())
                .orElseThrow(() -> new CardException("Nie znaleziono karty demonstracyjnej."));
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

        var expectedAmount = currency == CurrencyCode.PLN
                ? amountPln.setScale(2)
                : amountPln.divide(exchangeRateRepository.findById(currency)
                        .orElseThrow(() -> new CardException("Brak aktywnego kursu dla wybranej waluty."))
                        .getRateToPln(), 2, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
        if (expectedAmount.compareTo(amount) != 0) {
            throw new CardException("Kurs platnosci wygasl. Pobierz nowe przeliczenie.");
        }
        var amountInPln = amountPln.setScale(2);
        var now = Instant.now(clock);
        var dayStart = now.atZone(BANK_ZONE).toLocalDate().atStartOfDay(BANK_ZONE).toInstant();
        var dayEnd = dayStart.plus(Duration.ofDays(1));
        var dailySpent = nonNull(cardTransactionRepository.sumCompletedAmountInPln(card.getId(), dayStart, dayEnd));
        var reason = dailySpent.add(amountInPln).compareTo(limit(card.getDailyLimit())) > 0
                ? "Przekroczono dzienny limit karty."
                : null;
        if (reason == null) {
            try {
                if (currency == CurrencyCode.PLN) {
                    source.debit(amount);
                } else {
                    balanceRepository.findByAccountIdAndCurrencyForUpdate(source.getId(), currency)
                            .orElseThrow(() -> new CardException("Brak subkonta w wybranej walucie."))
                            .debit(amount);
                }
                destination.credit(amountInPln);
            } catch (IllegalStateException exception) {
                reason = "Brak wystarczajacych srodkow w wybranej walucie.";
            }
        }

        var cardTransaction = cardTransactionRepository.saveAndFlush(new CardTransaction(
                card,
                "NovaTech Shop",
                currency,
                amount.setScale(2),
                amountInPln,
                key,
                CardPaymentChannel.ONLINE,
                reason == null ? CardTransactionStatus.COMPLETED : CardTransactionStatus.DECLINED,
                reason
        ));
        String reference = null;
        if (reason == null && bankTransactionRepository != null) {
            reference = "CARD" + UUID.randomUUID().toString().replace("-", "").substring(0, 28);
            bankTransactionRepository.saveAndFlush(BankTransaction.payment(
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
            ));
        }
        return new OnlinePaymentResult(
                cardTransaction.getStatus(), amount, currency, amountInPln, reason, reference,
                source.getAccountNumber(), false
        );
    }

    @Transactional
    public void block(String email) {
        var account = lockedAccount(email);
        cardRepository.findByAccountId(account.getId()).ifPresent(DemoCard::block);
    }

    @Transactional
    public void blockTemporarily(String email, int minutes) {
        if (minutes < 1 || minutes > 10080) {
            throw new CardException("Czas blokady musi miec od 1 minuty do 7 dni.");
        }
        var account = lockedAccount(email);
        cardRepository.findByAccountId(account.getId()).ifPresent(card ->
                card.temporarilyBlock(Instant.now(clock).plusSeconds(minutes * 60L))
        );
    }

    @Transactional
    public void activate(String email) {
        var account = lockedAccount(email);
        cardRepository.findByAccountId(account.getId()).ifPresent(DemoCard::activate);
    }

    private Account lockedAccount(String email) {
        return accountRepository.findByOwnerEmailForUpdate(normalize(email))
                .orElseThrow(() -> new CardException("Zalogowany uzytkownik nie posiada rachunku."));
    }

    private BigDecimal amountInPln(CurrencyCode currency, BigDecimal amount) {
        var rate = currency == CurrencyCode.PLN || exchangeRateRepository == null
                ? BigDecimal.ONE
                : exchangeRateRepository.findById(currency)
                .map(exchangeRate -> exchangeRate.getRateToPln())
                .orElseThrow(() -> new CardException("Brak aktywnego kursu dla wybranej waluty."));
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private static TransactionResult result(CardTransaction transaction) {
        return new TransactionResult(
                transaction.getCurrency(),
                transaction.getAmount(),
                transaction.getStatus(),
                transaction.getReason(),
                transaction.getChannel()
        );
    }

    private static void validate(
            String merchantName,
            CurrencyCode currency,
            CardPaymentChannel channel,
            BigDecimal amount,
            String idempotencyKey
    ) {
        if (merchantName == null || merchantName.isBlank() || merchantName.length() > 120) {
            throw new CardException("Podaj poprawna nazwe sprzedawcy.");
        }
        if (currency == null) {
            throw new CardException("Wybierz walute transakcji.");
        }
        if (channel == null) {
            throw new CardException("Wybierz kanal platnosci.");
        }
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 || amount.precision() > 19) {
            throw new CardException("Podaj dodatnia kwote z maksymalnie dwoma miejscami po przecinku.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
            throw new CardException("Brak poprawnego klucza operacji.");
        }
    }

    private static BigDecimal limit(BigDecimal value) {
        return value == null ? DEFAULT_DAILY_LIMIT : value.setScale(2);
    }

    private static BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2);
    }

    private static String maskedNumber() {
        return "**** **** **** " + "%04d".formatted(ThreadLocalRandom.current().nextInt(10_000));
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static Account accountById(List<Account> accounts, Long id) {
        return accounts.stream()
                .filter(account -> account.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new CardException("Nie znaleziono zablokowanego rachunku."));
    }

    private static void validateOnline(
            String onlineNumber,
            String cvv,
            String expiry,
            String merchantAccountNumber,
            String orderId,
            CurrencyCode currency,
            BigDecimal amountPln,
            BigDecimal amount,
            String title,
            String idempotencyKey
    ) {
        if (onlineNumber == null || !onlineNumber.strip().matches("\\d{16}")) {
            throw new CardException("Podaj 16-cyfrowy numer karty demonstracyjnej.");
        }
        if (cvv == null || !cvv.strip().matches("\\d{3}")) {
            throw new CardException("Podaj poprawny kod CVV.");
        }
        parseExpiry(expiry);
        if (merchantAccountNumber == null || !merchantAccountNumber.matches("\\d{26}")) {
            throw new CardException("Nieprawidlowy rachunek odbiorcy.");
        }
        if (orderId == null || orderId.isBlank() || orderId.length() > 100) {
            throw new CardException("Nieprawidlowy numer zamowienia.");
        }
        if (currency == null || amountPln == null || amountPln.signum() <= 0
                || amountPln.scale() > 2 || amountPln.precision() > 19
                || amount == null || amount.signum() <= 0
                || amount.scale() > 2 || amount.precision() > 19) {
            throw new CardException("Podaj dodatnia kwote z maksymalnie dwoma miejscami po przecinku.");
        }
        if (title == null || title.isBlank() || title.length() > 140) {
            throw new CardException("Podaj poprawny tytul platnosci.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
            throw new CardException("Brak poprawnego klucza operacji.");
        }
    }

    private static java.time.YearMonth parseExpiry(String expiry) {
        if (expiry == null || expiry.isBlank()) {
            throw new CardException("Data waznosci karty musi miec format MM/RR.");
        }
        try {
            return java.time.YearMonth.parse(expiry.strip(),
                    java.time.format.DateTimeFormatter.ofPattern("MM/yy"));
        } catch (java.time.DateTimeException exception) {
            throw new CardException("Data waznosci karty musi miec format MM/RR.");
        }
    }

    private static OnlinePaymentResult onlineResult(CardTransaction transaction, String reference, boolean replayed) {
        return new OnlinePaymentResult(
                transaction.getStatus(), transaction.getAmount(), transaction.getCurrency(),
                transaction.getAmountInPln(), transaction.getReason(), reference,
                transaction.getCard().getAccount().getAccountNumber(), replayed
        );
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
