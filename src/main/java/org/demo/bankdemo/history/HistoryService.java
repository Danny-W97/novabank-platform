package org.demo.bankdemo.history;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.transaction.BankTransaction;
import org.demo.bankdemo.transaction.TransactionRepository;
import org.demo.bankdemo.transaction.TransactionStatus;
import org.demo.bankdemo.transaction.TransactionType;
import org.demo.bankdemo.transfer.TransferForm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;

@Service
public class HistoryService {

    static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final Locale POLISH = Locale.forLanguageTag("pl-PL");
    private static final int PAGE_SIZE = 10;
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", POLISH).withZone(WARSAW);

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public HistoryService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public HistoryPage historyFor(String authenticatedEmail, HistoryFilter filter) {
        var account = accountFor(authenticatedEmail);
        var specification = HistorySpecifications.visibleTo(account.getId(), filter);
        var requestedPage = Math.max(0, filter.getPage());
        var pageable = PageRequest.of(
                requestedPage,
                PAGE_SIZE,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
        );
        var page = transactionRepository.findAll(specification, pageable);

        if (page.getTotalPages() > 0 && requestedPage >= page.getTotalPages()) {
            requestedPage = page.getTotalPages() - 1;
            filter.setPage(requestedPage);
            pageable = PageRequest.of(
                    requestedPage,
                    PAGE_SIZE,
                    Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
            );
            page = transactionRepository.findAll(specification, pageable);
        }

        var pageNumber = requestedPage;
        var transactions = page.getContent().stream()
                .map(transaction -> toItem(transaction, account.getId()))
                .toList();
        var firstElement = page.isEmpty() ? 0 : (long) pageNumber * PAGE_SIZE + 1;
        var lastElement = page.isEmpty() ? 0 : firstElement + page.getNumberOfElements() - 1;

        return new HistoryPage(
                transactions,
                page.getTotalElements(),
                pageNumber,
                page.getTotalPages(),
                firstElement,
                lastElement,
                pageNumber > 0,
                pageNumber + 1 < page.getTotalPages(),
                pageNumbers(pageNumber, page.getTotalPages()),
                filter.hasActiveFilters()
        );
    }

    @Transactional(readOnly = true)
    public HistoryDetail detailFor(
            String authenticatedEmail,
            Long transactionId
    ) {
        var account = accountFor(authenticatedEmail);
        var specification = HistorySpecifications.visibleTo(account.getId(), new HistoryFilter())
                .and(HistorySpecifications.withId(transactionId));
        var transaction = transactionRepository.findOne(specification)
                .orElseThrow(HistoryNotFoundException::new);
        return toDetail(transaction, account);
    }

    @Transactional(readOnly = true)
    public boolean canRetry(String authenticatedEmail, Long transactionId) {
        var account = accountFor(authenticatedEmail);
        var transaction = visibleTransactionFor(account, transactionId);
        return transaction.getType() == TransactionType.TRANSFER
                && transaction.getStatus() == TransactionStatus.COMPLETED
                && isOutgoing(transaction, account.getId());
    }

    @Transactional(readOnly = true)
    public TransferForm retryFormFor(String authenticatedEmail, Long transactionId) {
        var account = accountFor(authenticatedEmail);
        var transaction = visibleTransactionFor(account, transactionId);
        if (transaction.getType() != TransactionType.TRANSFER
                || transaction.getStatus() != TransactionStatus.COMPLETED
                || !isOutgoing(transaction, account.getId())) {
            throw new HistoryNotFoundException();
        }

        var form = new TransferForm();
        form.setIdempotencyKey(UUID.randomUUID().toString());
        form.setRecipientName(transaction.getRecipientNameSnapshot());
        form.setDestinationAccountNumber(transaction.getDestinationAccount().getAccountNumber());
        form.setAmount(transaction.getAmount().toPlainString());
        form.setTitle(transaction.getTitle());
        return form;
    }

    private BankTransaction visibleTransactionFor(Account account, Long transactionId) {
        return transactionRepository.findOne(
                        HistorySpecifications.visibleTo(account.getId(), new HistoryFilter())
                                .and(HistorySpecifications.withId(transactionId))
                )
                .orElseThrow(HistoryNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public List<ExportRow> exportRowsFor(String authenticatedEmail, HistoryFilter filter) {
        var account = accountFor(authenticatedEmail);
        return transactionRepository.findAll(
                        HistorySpecifications.visibleTo(account.getId(), filter),
                        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
                ).stream()
                .map(transaction -> {
                    var outgoing = isOutgoing(transaction, account.getId());
                    var initialCredit = transaction.getType() == TransactionType.INITIAL_CREDIT;
                    return new ExportRow(
                            DATE_TIME_FORMAT.format(transaction.getCreatedAt()),
                            typeLabel(transaction.getType()),
                            initialCredit ? "Uznanie początkowe" : outgoing ? "Wychodząca" : "Przychodząca",
                            counterparty(transaction, outgoing, initialCredit),
                            transaction.getTitle(),
                            signedMoney(transaction.getAmount(), outgoing),
                            transaction.getCurrency(),
                            statusLabel(transaction.getStatus()),
                            transaction.getReferenceNumber()
                    );
                })
                .toList();
    }

    private Account accountFor(String authenticatedEmail) {
        var normalizedEmail = authenticatedEmail.strip().toLowerCase(Locale.ROOT);
        return accountRepository.findByOwnerEmail(normalizedEmail)
                .orElseThrow(() -> new IllegalStateException(
                        "Zalogowany użytkownik nie posiada rachunku."
                ));
    }

    private static HistoryPage.TransactionItem toItem(
            BankTransaction transaction,
            Long accountId
    ) {
        var outgoing = isOutgoing(transaction, accountId);
        var initialCredit = transaction.getType() == TransactionType.INITIAL_CREDIT;
        var counterparty = counterparty(transaction, outgoing, initialCredit);
        var icon = initialCredit
                ? "bi-stars"
                : outgoing ? "bi-arrow-up-right" : "bi-arrow-down-left";

        return new HistoryPage.TransactionItem(
                transaction.getId(),
                icon,
                outgoing ? "red" : "green",
                counterparty,
                transaction.getTitle(),
                DATE_TIME_FORMAT.format(transaction.getCreatedAt()),
                statusLabel(transaction.getStatus()),
                signedMoney(transaction.getAmount(), outgoing),
                outgoing ? "expense" : "income"
        );
    }

    private static HistoryDetail toDetail(BankTransaction transaction, Account ownAccount) {
        var outgoing = isOutgoing(transaction, ownAccount.getId());
        var initialCredit = transaction.getType() == TransactionType.INITIAL_CREDIT;
        var counterpartyAccount = initialCredit
                ? "—"
                : outgoing
                ? formatAccountNumber(transaction.getDestinationAccount().getAccountNumber())
                : formatAccountNumber(transaction.getSourceAccount().getAccountNumber());

        return new HistoryDetail(
                transaction.getId(),
                transaction.getReferenceNumber(),
                typeLabel(transaction.getType()),
                statusLabel(transaction.getStatus()),
                initialCredit ? "Uznanie początkowe" : outgoing ? "Wychodząca" : "Przychodząca",
                counterparty(transaction, outgoing, initialCredit),
                counterpartyAccount,
                formatAccountNumber(ownAccount.getAccountNumber()),
                signedMoney(transaction.getAmount(), outgoing),
                transaction.getTitle(),
                DATE_TIME_FORMAT.format(transaction.getCreatedAt()),
                transaction.getCompletedAt() == null
                        ? "—"
                        : DATE_TIME_FORMAT.format(transaction.getCompletedAt())
        );
    }

    private static boolean isOutgoing(BankTransaction transaction, Long accountId) {
        return transaction.getSourceAccount() != null
                && transaction.getSourceAccount().getId().equals(accountId);
    }

    private static String counterparty(
            BankTransaction transaction,
            boolean outgoing,
            boolean initialCredit
    ) {
        if (initialCredit) {
            return "NovaBank";
        }
        var value = outgoing
                ? transaction.getRecipientNameSnapshot()
                : transaction.getSenderNameSnapshot();
        return value == null || value.isBlank() ? transaction.getTitle() : value;
    }

    private static String typeLabel(TransactionType type) {
        return switch (type) {
            case INITIAL_CREDIT -> "Saldo startowe";
            case TRANSFER -> "Przelew";
            case PAYMENT -> "Płatność";
        };
    }

    private static String statusLabel(TransactionStatus status) {
        return switch (status) {
            case PENDING -> "Oczekująca";
            case COMPLETED -> "Zrealizowana";
            case FAILED -> "Odrzucona";
        };
    }

    private static List<Integer> pageNumbers(int currentPage, int totalPages) {
        if (totalPages <= 0) {
            return List.of();
        }
        var start = Math.max(0, Math.min(currentPage - 2, totalPages - 5));
        var endExclusive = Math.min(totalPages, start + 5);
        return IntStream.range(start, endExclusive).boxed().toList();
    }

    private static String signedMoney(BigDecimal amount, boolean outgoing) {
        return (outgoing ? "−" : "+") + moneyFormat().format(amount.abs()) + " zł";
    }

    private static DecimalFormat moneyFormat() {
        var symbols = DecimalFormatSymbols.getInstance(POLISH);
        symbols.setGroupingSeparator('\u00A0');
        return new DecimalFormat("#,##0.00", symbols);
    }

    private static String formatAccountNumber(String accountNumber) {
        var formatted = new StringBuilder(accountNumber.substring(0, 2));
        for (int index = 2; index < accountNumber.length(); index += 4) {
            formatted.append(' ').append(accountNumber, index, Math.min(index + 4, accountNumber.length()));
        }
        return formatted.toString();
    }

    public record ExportRow(
            String createdAt,
            String type,
            String direction,
            String counterparty,
            String title,
            String amount,
            String currency,
            String status,
            String referenceNumber
    ) {
    }}
