package org.demo.bankdemo.payment;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.account.AccountStatus;
import org.demo.bankdemo.config.TransferLimitsProperties;
import org.demo.bankdemo.notification.NotificationService;
import org.demo.bankdemo.transaction.BankTransaction;
import org.demo.bankdemo.transaction.TransactionRepository;
import org.demo.bankdemo.transaction.TransactionType;
import org.demo.bankdemo.transfer.TransactionReferenceGenerator;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class PaymentService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionReferenceGenerator referenceGenerator;
    private final NotificationService notificationService;
    private final Clock clock;
    private final TransferLimitsProperties bankLimits;

    @Autowired
    public PaymentService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            TransactionReferenceGenerator referenceGenerator,
            NotificationService notificationService,
            Clock clock,
            TransferLimitsProperties bankLimits
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.referenceGenerator = referenceGenerator;
        this.notificationService = notificationService;
        this.clock = clock;
        this.bankLimits = bankLimits;
    }

    public PaymentService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            TransactionReferenceGenerator referenceGenerator,
            NotificationService notificationService,
            Clock clock
    ) {
        this(accountRepository, transactionRepository, referenceGenerator, notificationService,
                clock, new TransferLimitsProperties());
    }

    @Transactional
    public PaymentResponse execute(String idempotencyKey, PaymentRequest request) {
        var normalizedKey = normalizeKey(idempotencyKey);
        var existing = transactionRepository.findByIdempotencyKey(normalizedKey);
        if (existing.isPresent()) {
            return existingPayment(existing.get(), request);
        }

        var sourceCandidate = account(request.customerAccountNumber());
        var destinationCandidate = account(request.merchantAccountNumber());
        validateAccounts(sourceCandidate, destinationCandidate, request);
        validateLimits(sourceCandidate, request);

        var accountIds = List.of(sourceCandidate.getId(), destinationCandidate.getId())
                .stream()
                .sorted()
                .toList();
        var lockedAccounts = accountRepository.findAllByIdForUpdate(accountIds);
        if (lockedAccounts.size() != 2) {
            throw new PaymentException("Nie mozna zablokowac rachunkow do platnosci.");
        }

        existing = transactionRepository.findByIdempotencyKey(normalizedKey);
        if (existing.isPresent()) {
            return existingPayment(existing.get(), request);
        }

        var source = accountById(lockedAccounts, sourceCandidate.getId());
        var destination = accountById(lockedAccounts, destinationCandidate.getId());
        validateAccounts(source, destination, request);

        source.debit(request.amount());
        destination.credit(request.amount());

        var completedAt = Instant.now(clock);
        var transaction = BankTransaction.payment(
                referenceGenerator.generate(),
                normalizedKey,
                request.orderId(),
                source,
                destination,
                source.getOwner().getFullName(),
                destination.getOwner().getFullName(),
                request.amount(),
                request.title(),
                completedAt
        );
        transactionRepository.saveAndFlush(transaction);
        notificationService.paymentCompleted(
                source.getOwner(),
                destination.getOwner(),
                request.amount().toPlainString() + " " + request.currency(),
                transaction.getReferenceNumber()
        );
        return response(transaction, false);
    }

    @Transactional
    public PaymentResponse refundByReference(
            String idempotencyKey,
            String originalReferenceNumber,
            String merchantAccountNumber,
            String orderId,
            BigDecimal amount,
            String currency,
            String title
    ) {
        var original = transactionRepository.findByReferenceNumber(originalReferenceNumber)
                .orElseThrow(() -> new PaymentException("Nie znaleziono oryginalnej platnosci."));
        if (original.getType() != TransactionType.PAYMENT
                || original.getSourceAccount() == null
                || !original.getDestinationAccount().getAccountNumber().equals(merchantAccountNumber)
                || !original.getPaymentOrderId().equals(orderId)
                || original.getAmount().compareTo(amount) != 0
                || !original.getCurrency().equals(currency)) {
            throw new PaymentException("Referencja nie pasuje do zwracanej platnosci.");
        }
        return execute(
                idempotencyKey,
                new PaymentRequest(
                        merchantAccountNumber,
                        original.getSourceAccount().getAccountNumber(),
                        "refund:" + orderId,
                        amount,
                        currency,
                        title
                )
        );
    }

    private PaymentResponse existingPayment(BankTransaction transaction, PaymentRequest request) {
        if (transaction.getType() != TransactionType.PAYMENT
                || transaction.getSourceAccount() == null
                || !transaction.getSourceAccount().getAccountNumber().equals(request.customerAccountNumber())
                || !transaction.getDestinationAccount().getAccountNumber().equals(request.merchantAccountNumber())
                || transaction.getPaymentOrderId() == null
                || !transaction.getPaymentOrderId().equals(request.orderId())
                || transaction.getAmount().compareTo(request.amount()) != 0
                || !transaction.getCurrency().equals(request.currency())
                || !transaction.getTitle().equals(request.title())) {
            throw new PaymentException("Klucz operacji zostal juz wykorzystany dla innych danych.");
        }
        return response(transaction, true);
    }

    private Account account(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new PaymentException("Nie znaleziono rachunku."));
    }

    private void validateLimits(Account source, PaymentRequest request) {
        if (request.amount().compareTo(bankLimits.getSingleLimit()) > 0) {
            throw new PaymentException(
                    "Kwota platnosci przekracza limit bankowy: "
                            + bankLimits.getSingleLimit().toPlainString() + " PLN."
            );
        }
        var dailyOverall = transactionRepository.sumOutgoingByEmailSince(
                source.getOwner().getEmail(),
                List.of(TransactionType.TRANSFER, TransactionType.PAYMENT),
                Instant.now(clock).minusSeconds(24 * 60 * 60)
        );
        if ((dailyOverall == null ? BigDecimal.ZERO : dailyOverall)
                .add(request.amount()).compareTo(bankLimits.getDailyLimit()) > 0) {
            throw new PaymentException(
                    "Przekroczono dzienny limit bankowy platnosci i przelewow: "
                            + bankLimits.getDailyLimit().toPlainString() + " PLN."
            );
        }
        var owner = source.getOwner();
        var paymentSingleLimit = owner.getPaymentSingleLimit();
        if (paymentSingleLimit != null && request.amount().compareTo(paymentSingleLimit) > 0) {
            throw new PaymentException(
                    "Kwota przekracza Twoj limit pojedynczej platnosci: "
                            + paymentSingleLimit.toPlainString() + " PLN."
            );
        }
        var dailyLimit = owner.getPaymentDailyLimit();
        if (dailyLimit != null) {
            var dailyPayments = transactionRepository.sumOutgoingByEmailSince(
                    owner.getEmail(),
                    List.of(TransactionType.PAYMENT),
                    Instant.now(clock).minusSeconds(24 * 60 * 60)
            );
            if ((dailyPayments == null ? BigDecimal.ZERO : dailyPayments)
                    .add(request.amount()).compareTo(dailyLimit) > 0) {
                throw new PaymentException(
                        "Przekroczono Twoj dzienny limit platnosci: "
                                + dailyLimit.toPlainString() + " PLN."
                );
            }
        }
    }

    private static Account accountById(List<Account> accounts, Long id) {
        return accounts.stream()
                .filter(account -> account.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new PaymentException("Nie znaleziono zablokowanego rachunku."));
    }

    private static void validateAccounts(Account source, Account destination, PaymentRequest request) {
        if (source.getId().equals(destination.getId())) {
            throw new PaymentException("Rachunek klienta i sklepu musza byc rozne.");
        }
        if (source.getStatus() != AccountStatus.ACTIVE || destination.getStatus() != AccountStatus.ACTIVE) {
            throw new PaymentException("Jeden z rachunkow nie jest aktywny.");
        }
        if (!source.getCurrency().equals(destination.getCurrency())
                || !source.getCurrency().equals(request.currency())) {
            throw new PaymentException("Nieprawidlowa waluta platnosci.");
        }
        if (source.getBalance().compareTo(request.amount()) < 0) {
            throw new PaymentException("Brak wystarczajacych srodkow.");
        }
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank() || key.length() > 64) {
            throw new PaymentException("Idempotency-Key jest wymagany i moze miec maksymalnie 64 znaki.");
        }
        return key.strip();
    }

    private static PaymentResponse response(BankTransaction transaction, boolean replayed) {
        return new PaymentResponse(
                transaction.getReferenceNumber(),
                transaction.getPaymentOrderId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus().name(),
                replayed
        );
    }
}
