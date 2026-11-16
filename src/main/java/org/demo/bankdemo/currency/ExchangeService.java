package org.demo.bankdemo.currency;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.account.AccountStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.scheduling.annotation.Scheduled;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

@Service
public class ExchangeService {

    private static final Locale POLISH = Locale.forLanguageTag("pl-PL");
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", POLISH).withZone(WARSAW);
    private static final Duration QUOTE_LIFETIME = Duration.ofMinutes(10);
    private static final int RATE_SCALE = 8;

    private final AccountRepository accountRepository;
    private final CurrencyBalanceRepository balanceRepository;
    private final ExchangeRateRepository rateRepository;
    private final ExchangeTransactionRepository exchangeRepository;
    private final ExchangeQuoteRepository quoteRepository;
    private final Clock clock;

    public ExchangeService(
            AccountRepository accountRepository,
            CurrencyBalanceRepository balanceRepository,
            ExchangeRateRepository rateRepository,
            ExchangeTransactionRepository exchangeRepository,
            ExchangeQuoteRepository quoteRepository,
            Clock clock
    ) {
        this.accountRepository = accountRepository;
        this.balanceRepository = balanceRepository;
        this.rateRepository = rateRepository;
        this.exchangeRepository = exchangeRepository;
        this.quoteRepository = quoteRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<BalanceView> balancesFor(String email) {
        var account = accountRepository.findByOwnerEmail(normalize(email))
                .orElseThrow(() -> new ExchangeException("Zalogowany użytkownik nie posiada rachunku."));
        var balances = new EnumMap<CurrencyCode, BigDecimal>(CurrencyCode.class);
        balances.put(CurrencyCode.PLN, scale(account.getBalance()));
        balanceRepository.findAllByAccountIdOrderByCurrencyAsc(account.getId())
                .forEach(value -> balances.put(value.getCurrency(), scale(value.getBalance())));
        return List.of(CurrencyCode.values()).stream()
                .map(currency -> new BalanceView(currency, balances.getOrDefault(currency, BigDecimal.ZERO.setScale(2))))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RateView> rates() {
        return rateRepository.findAllByOrderByCurrencyAsc().stream()
                .map(rate -> new RateView(rate.getCurrency(), rate.getRateToPln(), rate.isActive()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<HistoryView> historyFor(String email) {
        var account = accountRepository.findByOwnerEmail(normalize(email))
                .orElseThrow(() -> new ExchangeException("Zalogowany użytkownik nie posiada rachunku."));
        return exchangeRepository.findByAccountIdOrderByCreatedAtDesc(account.getId(), PageRequest.of(0, 30))
                .stream()
                .map(transaction -> new HistoryView(
                        transaction.getSourceCurrency(),
                        transaction.getTargetCurrency(),
                        transaction.getSourceAmount(),
                        transaction.getTargetAmount(),
                        transaction.getAppliedRate(),
                        transaction.getStatus(),
                        DATE_TIME.format(transaction.getCreatedAt())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public Quote quote(CurrencyCode source, CurrencyCode target, BigDecimal amount) {
        validateCurrencies(source, target);
        validateAmount(amount);
        var sourceRate = rateFor(source);
        var targetRate = rateFor(target);
        var appliedRate = sourceRate.divide(targetRate, RATE_SCALE, RoundingMode.HALF_UP);
        var targetAmount = amount.multiply(appliedRate).setScale(2, RoundingMode.HALF_UP);
        return new Quote(
                source,
                target,
                amount.setScale(2),
                targetAmount,
                appliedRate,
                Instant.now(clock)
        );
    }

    @Transactional(timeoutString = "${app.database.transaction-timeout-seconds:15}")
    public PreparedQuote prepareQuote(String email, CurrencyCode source, CurrencyCode target, BigDecimal amount) {
        var quote = quote(source, target, amount);
        validateQuote(quote);
        var key = UUID.randomUUID().toString();
        quoteRepository.saveAndFlush(new ExchangeQuote(key, normalize(email), quote));
        return new PreparedQuote(key, quote);
    }

    @Transactional(readOnly = true)
    public Optional<Quote> pendingQuoteFor(String email, String key) {
        return quoteRepository.findById(normalizeKey(key))
                .filter(quote -> quote.getOwnerEmail().equals(normalize(email)))
                .map(ExchangeQuote::toQuote)
                .filter(quote -> !quote.createdAt().plus(QUOTE_LIFETIME).isBefore(Instant.now(clock)));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeoutString = "${app.database.transaction-timeout-seconds:15}")
    public ExchangeResult executePrepared(String email, String idempotencyKey) {
        var key = normalizeKey(idempotencyKey);
        var owner = normalize(email);
        var existing = exchangeRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            if (!existing.get().getAccount().getOwner().getEmail().equals(owner)) {
                throw new ExchangeException("Nie znaleziono wyceny dla tej operacji.");
            }
            return toResult(existing.get());
        }
        var prepared = quoteRepository.findById(key)
                .filter(quote -> quote.getOwnerEmail().equals(owner))
                .orElseThrow(() -> new ExchangeException("Wycena wygasła lub nie należy do tej operacji."));
        return execute(owner, prepared.toQuote(), key);
    }

    @Scheduled(fixedDelayString = "${app.exchange.quote-cleanup-delay-ms:3600000}")
    @Transactional(timeoutString = "${app.database.transaction-timeout-seconds:15}")
    public void deleteExpiredQuotes() {
        // Completed transactions retain their key and remain replayable even after quote cleanup.
        quoteRepository.deleteCreatedBefore(Instant.now(clock).minus(Duration.ofDays(1)));
    }

    @Transactional(timeoutString = "${app.database.transaction-timeout-seconds:15}")
    ExchangeResult execute(String email, Quote quote, String idempotencyKey) {
        var key = normalizeKey(idempotencyKey);
        var normalizedEmail = normalize(email);
        var existing = exchangeRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return resultForExisting(existing.get(), normalizedEmail, quote);
        }

        var account = accountRepository.findByOwnerEmailForUpdate(normalizedEmail)
                .orElseThrow(() -> new ExchangeException("Zalogowany użytkownik nie posiada rachunku."));
        existing = exchangeRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return resultForExisting(existing.get(), normalizedEmail, quote);
        }
        validateQuote(quote);
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new ExchangeException("Rachunek nie jest aktywny.");
        }

        var sourceBalance = quote.sourceCurrency() == CurrencyCode.PLN
                ? null
                : balanceRepository.findByAccountIdAndCurrencyForUpdate(account.getId(), quote.sourceCurrency())
                .orElseThrow(() -> new ExchangeException("Brak subkonta w walucie źródłowej."));
        if (sourceBalance != null) {
            if (sourceBalance.getBalance().compareTo(quote.sourceAmount()) < 0) {
                throw new ExchangeException("amount", "Brak wystarczających środków w walucie źródłowej.");
            }
            sourceBalance.debit(quote.sourceAmount());
        } else {
            if (account.getBalance().compareTo(quote.sourceAmount()) < 0) {
                throw new ExchangeException("amount", "Brak wystarczających środków.");
            }
            account.debit(quote.sourceAmount());
        }

        if (quote.targetCurrency() == CurrencyCode.PLN) {
            account.credit(quote.targetAmount());
        } else {
            var targetBalance = balanceRepository.findByAccountIdAndCurrencyForUpdate(
                            account.getId(), quote.targetCurrency())
                    .orElseGet(() -> balanceRepository.save(
                            new CurrencyBalance(account, quote.targetCurrency())
                    ));
            targetBalance.credit(quote.targetAmount());
        }

        var transaction = exchangeRepository.saveAndFlush(new ExchangeTransaction(
                account,
                quote.sourceCurrency(),
                quote.targetCurrency(),
                quote.sourceAmount(),
                quote.targetAmount(),
                quote.appliedRate(),
                key
        ));
        return toResult(transaction);
    }

    @Transactional
    public void updateRate(CurrencyCode currency, BigDecimal rateToPln) {
        if (currency == CurrencyCode.PLN) {
            throw new ExchangeException("Kurs PLN musi pozostać równy 1,000000.");
        }
        if (rateToPln == null || rateToPln.signum() <= 0 || rateToPln.scale() > 6) {
            throw new ExchangeException("Podaj dodatni kurs z maksymalnie sześcioma miejscami po przecinku.");
        }
        var rate = rateRepository.findById(currency)
                .orElseThrow(() -> new ExchangeException("Nie znaleziono waluty."));
        rate.updateRate(rateToPln);
    }

    private ExchangeResult resultForExisting(
            ExchangeTransaction transaction,
            String email,
            Quote quote
    ) {
        if (!transaction.getAccount().getOwner().getEmail().equals(email)
                || transaction.getSourceCurrency() != quote.sourceCurrency()
                || transaction.getTargetCurrency() != quote.targetCurrency()
                || transaction.getSourceAmount().compareTo(quote.sourceAmount()) != 0
                || transaction.getTargetAmount().compareTo(quote.targetAmount()) != 0
                || transaction.getAppliedRate().compareTo(quote.appliedRate()) != 0) {
            throw new ExchangeException("Klucz operacji został już wykorzystany dla innej wymiany.");
        }
        return toResult(transaction);
    }

    private ExchangeRate rateEntity(CurrencyCode currency) {
        return rateRepository.findById(currency)
                .filter(ExchangeRate::isActive)
                .orElseThrow(() -> new ExchangeException("Kurs wybranej waluty jest niedostępny."));
    }

    private BigDecimal rateFor(CurrencyCode currency) {
        return rateEntity(currency).getRateToPln();
    }

    private static ExchangeResult toResult(ExchangeTransaction transaction) {
        return new ExchangeResult(
                transaction.getSourceCurrency(),
                transaction.getTargetCurrency(),
                transaction.getSourceAmount(),
                transaction.getTargetAmount(),
                transaction.getAppliedRate(),
                transaction.getIdempotencyKey()
        );
    }

    private static void validateCurrencies(CurrencyCode source, CurrencyCode target) {
        if (source == null || target == null || source == target) {
            throw new ExchangeException("Wybierz dwie różne waluty.");
        }
    }

    private static void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 || amount.precision() > 19) {
            throw new ExchangeException("amount", "Podaj dodatnią kwotę z maksymalnie dwoma miejscami po przecinku.");
        }
    }

    private void validateQuote(Quote quote) {
        if (quote == null || quote.createdAt() == null
                || quote.createdAt().plus(QUOTE_LIFETIME).isBefore(Instant.now(clock))) {
            throw new ExchangeException("Wycena wygasła. Pobierz nową wycenę.");
        }
        validateCurrencies(quote.sourceCurrency(), quote.targetCurrency());
        validateAmount(quote.sourceAmount());
        if (quote.targetAmount() == null || quote.targetAmount().signum() <= 0
                || quote.targetAmount().scale() > 2 || quote.targetAmount().precision() > 19
                || quote.appliedRate() == null || quote.appliedRate().signum() <= 0
                || quote.appliedRate().scale() > 8 || quote.appliedRate().precision() > 19) {
            throw new ExchangeException("Wycena ma niepoprawną kwotę docelową lub kurs.");
        }
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank() || key.length() > 64) {
            throw new ExchangeException("Brak poprawnego klucza operacji.");
        }
        return key.strip();
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public record Quote(
            CurrencyCode sourceCurrency,
            CurrencyCode targetCurrency,
            BigDecimal sourceAmount,
            BigDecimal targetAmount,
            BigDecimal appliedRate,
            Instant createdAt
    ) implements java.io.Serializable {
    }

    public record PreparedQuote(String idempotencyKey, Quote quote) {}

    public record BalanceView(CurrencyCode currency, BigDecimal amount) {
    }

    public record RateView(CurrencyCode currency, BigDecimal rateToPln, boolean active) {
    }

    public record HistoryView(
            CurrencyCode sourceCurrency,
            CurrencyCode targetCurrency,
            BigDecimal sourceAmount,
            BigDecimal targetAmount,
            BigDecimal appliedRate,
            ExchangeStatus status,
            String createdAt
    ) {
    }

    public record ExchangeResult(
            CurrencyCode sourceCurrency,
            CurrencyCode targetCurrency,
            BigDecimal sourceAmount,
            BigDecimal targetAmount,
            BigDecimal appliedRate,
            String idempotencyKey
    ) {
    }
}
