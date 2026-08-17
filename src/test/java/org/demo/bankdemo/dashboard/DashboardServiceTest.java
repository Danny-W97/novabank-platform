package org.demo.bankdemo.dashboard;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.transaction.TransactionStatus;
import org.demo.bankdemo.transaction.TransactionType;
import org.demo.bankdemo.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final long ACCOUNT_ID = 41L;
    private static final String ACCOUNT_NUMBER = "61109010140000071219812874";
    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private DashboardTransactionRepository transactionRepository;

    private DashboardService dashboardService;
    private Account account;

    @BeforeEach
    void setUp() {
        var user = new User(
                "anna.nowak@example.pl",
                "hash",
                "Anna",
                "Nowak",
                null
        );
        ReflectionTestUtils.setField(user, "id", 7L);
        account = new Account(user, ACCOUNT_NUMBER, new BigDecimal("10000.00"));
        ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);

        dashboardService = new DashboardService(
                accountRepository,
                transactionRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void buildsDashboardOnlyForAccountOwnedByAuthenticatedEmail() {
        when(accountRepository.findByOwnerEmail("anna.nowak@example.pl"))
                .thenReturn(Optional.of(account));
        when(transactionRepository.findRecent(
                eq(ACCOUNT_ID),
                eq(TransactionStatus.COMPLETED),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        )).thenReturn(List.of(
                new DashboardTransactionRow(
                        TransactionType.INITIAL_CREDIT,
                        null,
                        ACCOUNT_ID,
                        null,
                        "Anna Nowak",
                        new BigDecimal("10000.00"),
                        "PLN",
                        "Saldo startowe",
                        Instant.parse("2026-07-25T09:00:00Z")
                )
        ));
        when(transactionRepository.outgoingTotals(
                eq(ACCOUNT_ID),
                eq(TransactionStatus.COMPLETED),
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.any(Instant.class)
        )).thenReturn(totals(BigDecimal.ZERO, 0));
        when(transactionRepository.incomingTotals(
                eq(ACCOUNT_ID),
                eq(TransactionStatus.COMPLETED),
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.any(Instant.class)
        )).thenReturn(totals(new BigDecimal("10000.00"), 1));

        var dashboard = dashboardService.dashboardFor("  ANNA.NOWAK@EXAMPLE.PL ");

        assertThat(dashboard.fullName()).isEqualTo("Anna Nowak");
        assertThat(dashboard.initials()).isEqualTo("AN");
        assertThat(dashboard.accountNumber()).isEqualTo("61 1090 1014 0000 0712 1981 2874");
        assertThat(dashboard.balance()).isEqualTo("10 000,00 zł");
        assertThat(dashboard.expenses()).isEqualTo("−0,00 zł");
        assertThat(dashboard.income()).isEqualTo("+10 000,00 zł");
        assertThat(dashboard.transactions()).hasSize(1);
        assertThat(dashboard.transactions().getFirst().name()).isEqualTo("Saldo startowe");
        assertThat(dashboard.transactions().getFirst().direction()).isEqualTo("income");

        verify(accountRepository).findByOwnerEmail("anna.nowak@example.pl");
        verify(transactionRepository).findRecent(
                eq(ACCOUNT_ID),
                eq(TransactionStatus.COMPLETED),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        );
    }

    @Test
    void returnsEmptyStateWhenAccountHasNoTransactions() {
        when(accountRepository.findByOwnerEmail("anna.nowak@example.pl"))
                .thenReturn(Optional.of(account));
        when(transactionRepository.findRecent(
                eq(ACCOUNT_ID),
                eq(TransactionStatus.COMPLETED),
                org.mockito.ArgumentMatchers.any(Pageable.class)
        )).thenReturn(List.of());
        when(transactionRepository.outgoingTotals(
                eq(ACCOUNT_ID),
                eq(TransactionStatus.COMPLETED),
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.any(Instant.class)
        )).thenReturn(totals(null, 0));
        when(transactionRepository.incomingTotals(
                eq(ACCOUNT_ID),
                eq(TransactionStatus.COMPLETED),
                org.mockito.ArgumentMatchers.any(Instant.class),
                org.mockito.ArgumentMatchers.any(Instant.class)
        )).thenReturn(totals(null, 0));

        var dashboard = dashboardService.dashboardFor("anna.nowak@example.pl");

        assertThat(dashboard.transactions()).isEmpty();
        assertThat(dashboard.lastActivity()).isEqualTo("Brak operacji");
        assertThat(dashboard.expenseCount()).isZero();
        assertThat(dashboard.incomeCount()).isZero();
    }

    private static DashboardTotalsProjection totals(BigDecimal total, long count) {
        return new DashboardTotalsProjection() {
            @Override
            public BigDecimal getTotal() {
                return total;
            }

            @Override
            public long getTransactionCount() {
                return count;
            }
        };
    }
}
