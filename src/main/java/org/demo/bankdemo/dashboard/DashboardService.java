package org.demo.bankdemo.dashboard;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.transaction.TransactionStatus;
import org.demo.bankdemo.transaction.TransactionType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

@Service
public class DashboardService {

    private static final Locale POLISH = Locale.forLanguageTag("pl-PL");
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final DateTimeFormatter CURRENT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", POLISH);
    private static final DateTimeFormatter TRANSACTION_DATE_FORMAT =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", POLISH).withZone(WARSAW);

    private final AccountRepository accountRepository;
    private final DashboardTransactionRepository transactionRepository;
    private final Clock clock;

    public DashboardService(
            AccountRepository accountRepository,
            DashboardTransactionRepository transactionRepository,
            Clock clock
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardView dashboardFor(String authenticatedEmail) {
        var normalizedEmail = authenticatedEmail.strip().toLowerCase(Locale.ROOT);
        var account = accountRepository.findByOwnerEmail(normalizedEmail)
                .orElseThrow(() -> new IllegalStateException("Zalogowany użytkownik nie posiada rachunku."));

        var now = Instant.now(clock);
        var nowInWarsaw = now.atZone(WARSAW);
        var monthStart = nowInWarsaw.withDayOfMonth(1).toLocalDate().atStartOfDay(WARSAW).toInstant();
        var nextMonthStart = nowInWarsaw.withDayOfMonth(1).plusMonths(1)
                .toLocalDate().atStartOfDay(WARSAW).toInstant();

        var recentRows = transactionRepository.findRecent(
                account.getId(),
                TransactionStatus.COMPLETED,
                PageRequest.of(0, 5)
        );
        var outgoing = transactionRepository.outgoingTotals(
                account.getId(),
                TransactionStatus.COMPLETED,
                monthStart,
                nextMonthStart
        );
        var incoming = transactionRepository.incomingTotals(
                account.getId(),
                TransactionStatus.COMPLETED,
                monthStart,
                nextMonthStart
        );

        var owner = account.getOwner();
        var transactions = recentRows.stream()
                .map(row -> toTransactionItem(row, account.getId()))
                .toList();

        return new DashboardView(
                owner.getFirstName(),
                owner.getFullName(),
                initials(owner.getFirstName(), owner.getLastName()),
                owner.getEmail(),
                displayPhone(owner.getPhone()),
                "NB-%06d".formatted(owner.getId()),
                formatAccountNumber(account.getAccountNumber()),
                formatMoney(account.getBalance()),
                accountStatusLabel(account),
                capitalize(CURRENT_DATE_FORMAT.format(nowInWarsaw)),
                nowInWarsaw.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, POLISH),
                lastActivity(recentRows),
                formatSignedMoney(total(outgoing), true),
                count(outgoing),
                formatSignedMoney(total(incoming), false),
                count(incoming),
                transactions
        );
    }

    private static DashboardView.TransactionItem toTransactionItem(
            DashboardTransactionRow row,
            Long accountId
    ) {
        var outgoing = accountId.equals(row.sourceAccountId());
        var initialCredit = row.type() == TransactionType.INITIAL_CREDIT;
        var name = initialCredit
                ? row.title()
                : outgoing
                ? fallback(row.recipientName(), row.title())
                : fallback(row.senderName(), row.title());
        var icon = initialCredit
                ? "bi-stars"
                : outgoing ? "bi-arrow-up-right" : "bi-arrow-down-left";
        var color = outgoing ? "red" : "green";

        return new DashboardView.TransactionItem(
                icon,
                color,
                name,
                TRANSACTION_DATE_FORMAT.format(row.createdAt()),
                formatSignedMoney(row.amount(), outgoing),
                outgoing ? "expense" : "income"
        );
    }

    private static String fallback(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static String lastActivity(List<DashboardTransactionRow> transactions) {
        if (transactions.isEmpty()) {
            return "Brak operacji";
        }
        return "Ostatnia operacja: " + TRANSACTION_DATE_FORMAT.format(transactions.getFirst().createdAt());
    }

    private static BigDecimal total(DashboardTotalsProjection totals) {
        return totals == null || totals.getTotal() == null ? BigDecimal.ZERO : totals.getTotal();
    }

    private static long count(DashboardTotalsProjection totals) {
        return totals == null ? 0 : totals.getTransactionCount();
    }

    private static String initials(String firstName, String lastName) {
        return (firstName.substring(0, 1) + lastName.substring(0, 1)).toUpperCase(POLISH);
    }

    private static String displayPhone(String phone) {
        return phone == null || phone.isBlank() ? "Nie podano" : phone;
    }

    private static String accountStatusLabel(Account account) {
        return switch (account.getStatus()) {
            case ACTIVE -> "Aktywne";
            case BLOCKED -> "Zablokowane";
            case CLOSED -> "Zamknięte";
        };
    }

    static String formatAccountNumber(String accountNumber) {
        var formatted = new StringBuilder(accountNumber.substring(0, 2));
        for (int index = 2; index < accountNumber.length(); index += 4) {
            formatted.append(' ').append(accountNumber, index, Math.min(index + 4, accountNumber.length()));
        }
        return formatted.toString();
    }

    private static String formatMoney(BigDecimal amount) {
        return moneyFormat().format(amount) + " zł";
    }

    private static String formatSignedMoney(BigDecimal amount, boolean outgoing) {
        var sign = outgoing ? "−" : "+";
        return sign + moneyFormat().format(amount.abs()) + " zł";
    }

    private static DecimalFormat moneyFormat() {
        var symbols = DecimalFormatSymbols.getInstance(POLISH);
        symbols.setGroupingSeparator('\u00A0');
        var format = new DecimalFormat("#,##0.00", symbols);
        format.setParseBigDecimal(true);
        return format;
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.substring(0, 1).toUpperCase(POLISH) + value.substring(1);
    }
}
