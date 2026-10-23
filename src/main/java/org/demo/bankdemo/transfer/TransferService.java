package org.demo.bankdemo.transfer;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountNumberGenerator;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.account.AccountStatus;
import org.demo.bankdemo.notification.NotificationService;
import org.demo.bankdemo.transaction.BankTransaction;
import org.demo.bankdemo.transaction.TransactionRepository;
import org.demo.bankdemo.transaction.TransactionType;
import org.demo.bankdemo.config.TransferLimitsProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class TransferService {

    private static final Locale POLISH = Locale.forLanguageTag("pl-PL");
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", POLISH)
                    .withZone(ZoneId.of("Europe/Warsaw"));

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionReferenceGenerator referenceGenerator;
    private final NotificationService notificationService;
    private final Clock clock;
    private final TransferLimitsProperties limitsProperties;
    private final TransferRateLimitService rateLimitService;

    public TransferService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            TransactionReferenceGenerator referenceGenerator,
            NotificationService notificationService,
            Clock clock,
            TransferLimitsProperties limitsProperties,
            TransferRateLimitService rateLimitService
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.referenceGenerator = referenceGenerator;
        this.notificationService = notificationService;
        this.clock = clock;
        this.limitsProperties = limitsProperties;
        this.rateLimitService = rateLimitService;
    }

    @Transactional(readOnly = true)
    public TransferPreview preview(String authenticatedEmail, TransferCommand command) {
        validateCommand(command);
        var source = sourceAccount(authenticatedEmail);
        var destination = destinationAccount(command.destinationAccountNumber());
        validateAccounts(source, destination, command.amount());

        return new TransferPreview(
                command.recipientName(),
                formatAccountNumber(destination.getAccountNumber()),
                formatMoney(command.amount()),
                command.title()
        );
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeoutString = "${app.database.transaction-timeout-seconds:15}")
    public TransferResult execute(String authenticatedEmail, TransferCommand command) {
        validateCommand(command);
        var normalizedEmail = normalizeEmail(authenticatedEmail);

        var existing = transactionRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return resultForExisting(existing.get(), normalizedEmail, command);
        }

        if (!rateLimitService.tryAcquire(normalizedEmail, limitsProperties.getRateLimitPerMinute())) {
            throw new TransferException(null, "Przekroczono limit prób przelewu. Spróbuj ponownie za minutę.");
        }
        if (command.amount().compareTo(limitsProperties.getSingleLimit()) > 0) {
            throw new TransferException(
                    "amount",
                    "Kwota przekracza limit pojedynczego przelewu: "
                            + limitsProperties.getSingleLimit().toPlainString() + " PLN."
            );
        }
        // Load only scalar IDs before locking: managed Account snapshots could otherwise be stale
        // after another transfer commits while this transaction waits for its pessimistic lock.
        var sourceId = accountRepository.findIdByOwnerEmail(normalizedEmail)
                .orElseThrow(() -> new TransferException(null, "Zalogowany użytkownik nie posiada rachunku."));
        var destinationId = accountRepository.findIdByAccountNumber(command.destinationAccountNumber())
                .orElseThrow(() -> new TransferException("destinationAccountNumber", "Nie znaleziono rachunku odbiorcy."));
        if (sourceId.equals(destinationId)) {
            throw new TransferException("destinationAccountNumber", "Nie można wykonać przelewu na własny rachunek.");
        }

        var accountIds = List.of(sourceId, destinationId).stream()
                .sorted()
                .toList();
        var lockedAccounts = accountRepository.findAllByIdForUpdate(accountIds);
        if (lockedAccounts.size() != 2) {
            throw new TransferException(null, "Nie udało się zablokować rachunków do przelewu.");
        }

        existing = transactionRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return resultForExisting(existing.get(), normalizedEmail, command);
        }

        var source = accountById(lockedAccounts, sourceId);
        var destination = accountById(lockedAccounts, destinationId);
        if (!source.getOwner().getEmail().equals(normalizedEmail)) {
            throw new TransferException(null, "Rachunek źródłowy nie należy do zalogowanego użytkownika.");
        }
        validateAccounts(source, destination, command.amount());
        var dailyTotal = transactionRepository.sumOutgoingByEmailSince(
                normalizedEmail,
                List.of(TransactionType.TRANSFER, TransactionType.PAYMENT),
                Instant.now(clock).minusSeconds(24 * 60 * 60)
        );
        if (safeTotal(dailyTotal).add(command.amount()).compareTo(limitsProperties.getDailyLimit()) > 0) {
            throw new TransferException(
                    "amount",
                    "Przekroczono dzienny limit przelewów: "
                            + limitsProperties.getDailyLimit().toPlainString() + " PLN."
            );
        }
        validateUserTransferLimits(source, command, normalizedEmail);

        source.debit(command.amount());
        destination.credit(command.amount());

        var completedAt = Instant.now(clock);
        var transaction = BankTransaction.transfer(
                referenceGenerator.generate(),
                command.idempotencyKey(),
                source,
                destination,
                source.getOwner().getFullName(),
                command.recipientName(),
                command.amount(),
                command.title(),
                completedAt
        );
        transactionRepository.saveAndFlush(transaction);
        notificationService.transferCompleted(
                source.getOwner(),
                destination.getOwner(),
                formatMoney(transaction.getAmount()),
                transaction.getReferenceNumber()
        );
        return toResult(transaction);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<TransferResult> findCompleted(String authenticatedEmail, TransferCommand command) {
        validateCommand(command);
        var email = normalizeEmail(authenticatedEmail);
        return transactionRepository.findByIdempotencyKey(command.idempotencyKey())
                .map(transaction -> resultForExisting(transaction, email, command));
    }

    @Transactional(readOnly = true)
    public TransferResult resultFor(String referenceNumber, String authenticatedEmail) {
        var transaction = transactionRepository.findByReferenceNumber(referenceNumber)
                .orElseThrow(() -> new TransferException(null, "Nie znaleziono przelewu."));
        var normalizedEmail = normalizeEmail(authenticatedEmail);
        if (transaction.getType() != TransactionType.TRANSFER
                || transaction.getSourceAccount() == null
                || !transaction.getSourceAccount().getOwner().getEmail().equals(normalizedEmail)) {
            throw new TransferException(null, "Nie znaleziono przelewu.");
        }
        return toResult(transaction);
    }

    private TransferResult resultForExisting(
            BankTransaction transaction,
            String normalizedEmail,
            TransferCommand command
    ) {
        if (transaction.getType() != TransactionType.TRANSFER
                || transaction.getSourceAccount() == null
                || !transaction.getSourceAccount().getOwner().getEmail().equals(normalizedEmail)
                || !transaction.getDestinationAccount().getAccountNumber()
                        .equals(command.destinationAccountNumber())
                || transaction.getAmount().compareTo(command.amount()) != 0
                || !transaction.getTitle().equals(command.title())
                || !transaction.getRecipientNameSnapshot().equals(command.recipientName())) {
            throw new TransferException(null, "Klucz operacji został już wykorzystany dla innego przelewu.");
        }
        return toResult(transaction);
    }

    private Account sourceAccount(String authenticatedEmail) {
        return accountRepository.findByOwnerEmail(normalizeEmail(authenticatedEmail))
                .orElseThrow(() -> new TransferException(null, "Zalogowany użytkownik nie posiada rachunku."));
    }

    private void validateUserTransferLimits(Account source, TransferCommand command, String email) {
        var owner = source.getOwner();
        var singleLimit = owner.getTransferSingleLimit();
        if (singleLimit != null && command.amount().compareTo(singleLimit) > 0) {
            throw new TransferException(
                    "amount",
                    "Kwota przekracza Twoj limit pojedynczego przelewu: " + singleLimit.toPlainString() + " PLN."
            );
        }
        var dailyLimit = owner.getTransferDailyLimit();
        if (dailyLimit != null) {
            var dailyTransfers = transactionRepository.sumOutgoingByEmailSince(
                    email,
                    List.of(TransactionType.TRANSFER),
                    Instant.now(clock).minusSeconds(24 * 60 * 60)
            );
            if (safeTotal(dailyTransfers).add(command.amount()).compareTo(dailyLimit) > 0) {
                throw new TransferException(
                        "amount",
                        "Przekroczono Twoj dzienny limit przelewow: " + dailyLimit.toPlainString() + " PLN."
                );
            }
        }
    }

    private static BigDecimal safeTotal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Account destinationAccount(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new TransferException(
                        "destinationAccountNumber",
                        "Nie znaleziono aktywnego rachunku odbiorcy."
                ));
    }

    private static Account accountById(List<Account> accounts, Long id) {
        return accounts.stream()
                .filter(account -> account.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new TransferException(null, "Nie znaleziono zablokowanego rachunku."));
    }

    private static void validateCommand(TransferCommand command) {
        if (command == null
                || command.idempotencyKey() == null
                || command.idempotencyKey().isBlank()) {
            throw new TransferException(null, "Brak klucza operacji.");
        }
        if (command.recipientName() == null
                || command.recipientName().isBlank()
                || command.recipientName().length() > 201) {
            throw new TransferException("recipientName", "Podaj poprawną nazwę odbiorcy.");
        }
        if (!AccountNumberGenerator.isValid(command.destinationAccountNumber())) {
            throw new TransferException(
                    "destinationAccountNumber",
                    "Podaj poprawny 26-cyfrowy numer rachunku NRB."
            );
        }
        if (command.amount() == null
                || command.amount().signum() <= 0
                || command.amount().scale() > 2
                || command.amount().precision() > 19) {
            throw new TransferException(
                    "amount",
                    "Podaj dodatnią kwotę z maksymalnie dwoma miejscami po przecinku."
            );
        }
        if (command.title() == null || command.title().isBlank() || command.title().length() > 140) {
            throw new TransferException("title", "Podaj poprawny tytuł przelewu.");
        }
    }

    private static void validateAccounts(Account source, Account destination, BigDecimal amount) {
        if (source.getId().equals(destination.getId())) {
            throw new TransferException(
                    "destinationAccountNumber",
                    "Nie można wykonać przelewu na własny rachunek."
            );
        }
        if (source.getStatus() != AccountStatus.ACTIVE) {
            throw new TransferException(null, "Rachunek źródłowy nie jest aktywny.");
        }
        if (destination.getStatus() != AccountStatus.ACTIVE) {
            throw new TransferException(
                    "destinationAccountNumber",
                    "Rachunek odbiorcy nie jest aktywny."
            );
        }
        if (!source.getCurrency().equals(destination.getCurrency())) {
            throw new TransferException(
                    "destinationAccountNumber",
                    "Rachunki muszą być prowadzone w tej samej walucie."
            );
        }
        if (source.getBalance().compareTo(amount) < 0) {
            throw new TransferException("amount", "Brak wystarczających środków na rachunku.");
        }
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static TransferResult toResult(BankTransaction transaction) {
        return new TransferResult(
                transaction.getReferenceNumber(),
                transaction.getRecipientNameSnapshot(),
                formatAccountNumber(transaction.getDestinationAccount().getAccountNumber()),
                formatMoney(transaction.getAmount()),
                transaction.getTitle(),
                DATE_TIME_FORMAT.format(transaction.getCompletedAt())
        );
    }

    private static String formatAccountNumber(String accountNumber) {
        var formatted = new StringBuilder(accountNumber.substring(0, 2));
        for (int index = 2; index < accountNumber.length(); index += 4) {
            formatted.append(' ').append(accountNumber, index, Math.min(index + 4, accountNumber.length()));
        }
        return formatted.toString();
    }

    private static String formatMoney(BigDecimal amount) {
        var symbols = DecimalFormatSymbols.getInstance(POLISH);
        symbols.setGroupingSeparator('\u00A0');
        var format = new DecimalFormat("#,##0.00", symbols);
        return format.format(amount) + " zł";
    }
}
