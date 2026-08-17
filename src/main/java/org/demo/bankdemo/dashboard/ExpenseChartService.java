package org.demo.bankdemo.dashboard;

import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.transaction.TransactionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

@Service
public class ExpenseChartService {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final Locale POLISH = Locale.forLanguageTag("pl-PL");

    private final AccountRepository accountRepository;
    private final DashboardTransactionRepository transactionRepository;
    private final Clock clock;

    public ExpenseChartService(
            AccountRepository accountRepository,
            DashboardTransactionRepository transactionRepository,
            Clock clock
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ExpensePoint> expensesFor(String email) {
        return expensesFor(email, 6);
    }

    @Transactional(readOnly = true)
    public List<ExpensePoint> expensesFor(String email, int requestedMonths) {
        var account = accountRepository.findByOwnerEmail(email.strip().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalStateException("Nie znaleziono rachunku."));
        var months = normalizePeriod(requestedMonths);
        var currentMonth = clock.instant().atZone(WARSAW).toLocalDate().withDayOfMonth(1);
        var firstMonth = currentMonth.minusMonths(months - 1L);
        var totals = new LinkedHashMap<String, BigDecimal>();
        for (var index = 0; index < months; index++) {
            totals.put(firstMonth.plusMonths(index).toString().substring(0, 7), BigDecimal.ZERO);
        }
        transactionRepository.monthlyExpenses(
                account.getId(),
                TransactionStatus.COMPLETED.name(),
                firstMonth.atStartOfDay(WARSAW).toInstant()
        ).forEach(row -> totals.put(row.getMonthKey(), row.getTotal()));

        var max = totals.values().stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        return totals.entrySet().stream()
                .map(entry -> {
                    var month = java.time.YearMonth.parse(entry.getKey());
                    var percent = max.signum() == 0
                            ? 0
                            : entry.getValue()
                                    .multiply(BigDecimal.valueOf(100))
                                    .divide(max, 0, RoundingMode.HALF_UP)
                                    .intValue();
                    return new ExpensePoint(
                            capitalize(month.getMonth().getDisplayName(TextStyle.SHORT, POLISH)),
                            entry.getValue().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                            percent
                    );
                })
                .toList();
    }

    public static int normalizePeriod(int requestedMonths) {
        return switch (requestedMonths) {
            case 3, 6, 12 -> requestedMonths;
            default -> 6;
        };
    }

    private static String capitalize(String value) {
        return value.substring(0, 1).toUpperCase(POLISH) + value.substring(1);
    }

    public record ExpensePoint(String month, String amount, int percent) {
    }
}
