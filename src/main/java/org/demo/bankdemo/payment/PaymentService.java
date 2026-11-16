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

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

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
    public PaymentResponse execute(String merchantId, String idempotencyKey, PaymentRequest request) {
        if (merchantId == null || !merchantId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("A stable merchant identity is required.");
        return executePayment(idempotencyKey, request, null, merchantId);
    }

    private PaymentResponse executePayment(String idempotencyKey, PaymentRequest request,
                                           BankTransaction refundOriginal, String merchantId) {
        var normalizedKey = normalizeKey(idempotencyKey);
        var existing = transactionRepository.findByIdempotencyKey(normalizedKey);
        if (existing.isPresent()) {
            validateRefundLink(existing.get(), refundOriginal);
            return existingPayment(existing.get(), request, merchantId);
        }

        var sourceId = accountRepository.findIdByAccountNumber(request.customerAccountNumber())
                .orElseThrow(() -> new PaymentException("Nie znaleziono rachunku klienta."));
        var destinationId = accountRepository.findIdByAccountNumber(request.merchantAccountNumber())
                .orElseThrow(() -> new PaymentException("Nie znaleziono rachunku sklepu."));
        if (sourceId.equals(destinationId)) throw new PaymentException("Rachunki musza byc rozne.");

        var accountIds = List.of(sourceId, destinationId)
                .stream()
                .sorted()
                .toList();
        var lockedAccounts = accountRepository.findAllByIdForUpdate(accountIds);
        if (lockedAccounts.size() != 2) {
            throw new PaymentException("Nie mozna zablokowac rachunkow do platnosci.");
        }

        existing = transactionRepository.findByIdempotencyKey(normalizedKey);
        if (existing.isPresent()) {
            validateRefundLink(existing.get(), refundOriginal);
            return existingPayment(existing.get(), request, merchantId);
        }

        lockedAccounts.forEach(account -> entityManager.refresh(account, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE));
        var source = accountById(lockedAccounts, sourceId);
        var destination = accountById(lockedAccounts, destinationId);
        validateAccounts(source, destination, request);
        validateLimits(source, request);

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
        if (refundOriginal != null) {
            transaction.markRefundOf(refundOriginal);
        }
        transaction.assignMerchant(merchantId);
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
            String customerAccountNumber,
            String orderId,
            BigDecimal amount,
            String currency,
            String title
    ) {
        var original = transactionRepository.findByReferenceNumberForUpdate(originalReferenceNumber)
                .orElseThrow(() -> new PaymentException("Nie znaleziono oryginalnej platnosci."));
        if (original.getType() != TransactionType.PAYMENT
                || original.getRefundOriginal() != null
                || original.getSourceAccount() == null
                || !original.getDestinationAccount().getAccountNumber().equals(merchantAccountNumber)
                || !orderId.equals(original.getPaymentOrderId())
                || (customerAccountNumber != null && !customerAccountNumber.isBlank()
                    && !original.getSourceAccount().getAccountNumber().equals(customerAccountNumber))
                || original.getAmount().compareTo(amount) != 0
                || !original.getCurrency().equals(currency)) {
            throw new PaymentException("Referencja nie pasuje do zwracanej platnosci.");
        }
        var request = new PaymentRequest(
                merchantAccountNumber,
                original.getSourceAccount().getAccountNumber(),
                "refund:" + orderId,
                amount,
                currency,
                title
        );
        var priorRefund = transactionRepository.findByRefundOriginalId(original.getId());
        if (priorRefund.isPresent()) {
            if (!priorRefund.get().getIdempotencyKey().equals(normalizeKey(idempotencyKey))) {
                throw new PaymentException("Ta platnosc zostala juz zwrocona.");
            }
            return existingPayment(priorRefund.get(), request, original.getMerchantId());
        }
        return executePayment(idempotencyKey, request, original, original.getMerchantId());
    }

    private static void validateRefundLink(BankTransaction existing, BankTransaction refundOriginal) {
        var existingOriginal = existing.getRefundOriginal();
        if (refundOriginal == null ? existingOriginal != null
                : existingOriginal == null || !existingOriginal.getId().equals(refundOriginal.getId())) {
            throw new PaymentException("Klucz operacji zostal juz wykorzystany dla innej platnosci.");
        }
    }

    private PaymentResponse existingPayment(BankTransaction transaction, PaymentRequest request, String merchantId) {
        if (transaction.getType() != TransactionType.PAYMENT
                || !java.util.Objects.equals(transaction.getMerchantId(), merchantId)
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
