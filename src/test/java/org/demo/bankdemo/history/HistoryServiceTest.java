package org.demo.bankdemo.history;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.transaction.BankTransaction;
import org.demo.bankdemo.transaction.TransactionRepository;
import org.demo.bankdemo.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoryServiceTest {

    private static final String OWN_NUMBER = "61109010140000071219812874";
    private static final String OTHER_NUMBER = "12127943138124663147042900";
    private static final Instant COMPLETED_AT = Instant.parse("2026-07-26T14:00:00Z");

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    private HistoryService historyService;
    private Account ownAccount;
    private Account otherAccount;

    @BeforeEach
    void setUp() {
        historyService = new HistoryService(accountRepository, transactionRepository);
        ownAccount = account(
                1L,
                new User("owner@example.pl", "hash", "Jan", "Kowalski", null),
                OWN_NUMBER
        );
        otherAccount = account(
                2L,
                new User("other@example.pl", "hash", "Anna", "Nowak", null),
                OTHER_NUMBER
        );
        when(accountRepository.findByOwnerEmail("owner@example.pl"))
                .thenReturn(Optional.of(ownAccount));
    }

    @Test
    void formatsIncomingAndOutgoingTransactionsFromPagedResult() {
        var outgoing = transfer(
                11L,
                "TRX-OUT",
                "key-out",
                ownAccount,
                otherAccount,
                "Jan Kowalski",
                "Anna Nowak",
                "125.50",
                "Przelew wychodzący"
        );
        var incoming = transfer(
                12L,
                "TRX-IN",
                "key-in",
                otherAccount,
                ownAccount,
                "Anna Nowak",
                "Jan Kowalski",
                "40.00",
                "Przelew przychodzący"
        );
        var page = new PageImpl<>(
                List.of(outgoing, incoming),
                PageRequest.of(0, 10),
                2
        );
        when(transactionRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenReturn(page);

        var result = historyService.historyFor(
                " OWNER@EXAMPLE.PL ",
                new HistoryFilter()
        );

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.firstElement()).isEqualTo(1);
        assertThat(result.lastElement()).isEqualTo(2);
        assertThat(result.transactions().get(0).counterparty()).isEqualTo("Anna Nowak");
        assertThat(result.transactions().get(0).amount()).isEqualTo("−125,50 zł");
        assertThat(result.transactions().get(0).direction()).isEqualTo("expense");
        assertThat(result.transactions().get(1).counterparty()).isEqualTo("Anna Nowak");
        assertThat(result.transactions().get(1).amount()).isEqualTo("+40,00 zł");
        assertThat(result.transactions().get(1).direction()).isEqualTo("income");

        var pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findAll(
                any(Specification.class),
                pageableCaptor.capture()
        );
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("createdAt").isDescending())
                .isTrue();
        assertThat(pageableCaptor.getValue().getSort().getOrderFor("id").isDescending())
                .isTrue();
    }

    @Test
    void clampsPageNumberToLastAvailablePage() {
        var filter = new HistoryFilter();
        filter.setPage(99);
        @SuppressWarnings("unchecked")
        var outsideRange = (Page<BankTransaction>) org.mockito.Mockito.mock(Page.class);
        when(outsideRange.getTotalPages()).thenReturn(3);
        var lastPage = new PageImpl<BankTransaction>(
                List.of(),
                PageRequest.of(2, 10),
                20
        );
        when(transactionRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenReturn(outsideRange, lastPage);

        var result = historyService.historyFor("owner@example.pl", filter);

        assertThat(result.currentPage()).isEqualTo(2);
        assertThat(filter.getPage()).isEqualTo(2);
    }

    @Test
    void mapsOwnedTransactionDetails() {
        var transaction = transfer(
                15L,
                "TRX-DETAIL",
                "key-detail",
                ownAccount,
                otherAccount,
                "Jan Kowalski",
                "Anna Nowak",
                "10.00",
                "Szczegóły"
        );
        when(transactionRepository.findOne(any(Specification.class)))
                .thenReturn(Optional.of(transaction));

        var detail = historyService.detailFor("owner@example.pl", 15L);

        assertThat(detail.referenceNumber()).isEqualTo("TRX-DETAIL");
        assertThat(detail.direction()).isEqualTo("Wychodząca");
        assertThat(detail.counterpartyAccount()).isEqualTo("12 1279 4313 8124 6631 4704 2900");
        assertThat(detail.ownAccount()).isEqualTo("61 1090 1014 0000 0712 1981 2874");
    }

    @Test
    void returnsNotFoundWhenOwnedSpecificationDoesNotFindTransaction() {
        when(transactionRepository.findOne(any(Specification.class)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> historyService.detailFor("owner@example.pl", 999L))
                .isInstanceOf(HistoryNotFoundException.class)
                .hasMessage("Nie znaleziono transakcji.");
    }

    private static BankTransaction transfer(
            Long id,
            String reference,
            String key,
            Account source,
            Account destination,
            String sender,
            String recipient,
            String amount,
            String title
    ) {
        var transaction = BankTransaction.transfer(
                reference,
                key,
                source,
                destination,
                sender,
                recipient,
                new BigDecimal(amount),
                title,
                COMPLETED_AT
        );
        ReflectionTestUtils.setField(transaction, "id", id);
        return transaction;
    }

    private static Account account(Long id, User owner, String accountNumber) {
        var account = new Account(owner, accountNumber, new BigDecimal("10000.00"));
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }
}
