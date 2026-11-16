package org.demo.bankdemo.currency;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.account.AccountStatus;
import org.demo.bankdemo.user.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ExchangeServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void calculatesQuoteFromStoredRates() {
        var rates = mock(ExchangeRateRepository.class);
        when(rates.findById(CurrencyCode.PLN)).thenReturn(Optional.of(new ExchangeRate(
                CurrencyCode.PLN, new BigDecimal("1.000000"))));
        when(rates.findById(CurrencyCode.EUR)).thenReturn(Optional.of(new ExchangeRate(
                CurrencyCode.EUR, new BigDecimal("4.350000"))));
        var service = service(rates);

        var quote = service.quote(CurrencyCode.PLN, CurrencyCode.EUR, new BigDecimal("435.00"));

        assertThat(quote.targetAmount()).isEqualByComparingTo("100.00");
        assertThat(quote.appliedRate()).isEqualByComparingTo("0.22988506");
    }

    @Test
    void rejectsSameCurrency() {
        var service = service(mock(ExchangeRateRepository.class));

        assertThatThrownBy(() -> service.quote(CurrencyCode.PLN, CurrencyCode.PLN, BigDecimal.ONE))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("różne waluty");
    }

    @Test
    void exchangesPlnToForeignBalanceAtomically() {
        var user = new User("client@example.pl", "hash", "Jan", "Kowalski", null);
        var account = mock(Account.class);
        when(account.getId()).thenReturn(12L);
        when(account.getOwner()).thenReturn(user);
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getBalance()).thenReturn(new BigDecimal("1000.00"));
        var target = mock(CurrencyBalance.class);
        var balances = mock(CurrencyBalanceRepository.class);
        when(balances.findByAccountIdAndCurrencyForUpdate(12L, CurrencyCode.EUR))
                .thenReturn(Optional.of(target));
        var exchanges = mock(ExchangeTransactionRepository.class);
        var transaction = mock(ExchangeTransaction.class);
        when(transaction.getAccount()).thenReturn(account);
        when(transaction.getSourceCurrency()).thenReturn(CurrencyCode.PLN);
        when(transaction.getTargetCurrency()).thenReturn(CurrencyCode.EUR);
        when(transaction.getSourceAmount()).thenReturn(new BigDecimal("100.00"));
        when(transaction.getTargetAmount()).thenReturn(new BigDecimal("22.99"));
        when(transaction.getAppliedRate()).thenReturn(new BigDecimal("0.22990000"));
        when(transaction.getIdempotencyKey()).thenReturn("exchange-1");
        when(exchanges.findByIdempotencyKey("exchange-1")).thenReturn(Optional.empty());
        when(exchanges.saveAndFlush(any())).thenReturn(transaction);
        var accountRepository = mock(AccountRepository.class);
        when(accountRepository.findByOwnerEmailForUpdate("client@example.pl"))
                .thenReturn(Optional.of(account));
        var service = new ExchangeService(
                accountRepository,
                balances,
                mock(ExchangeRateRepository.class),
                exchanges,
                mock(ExchangeQuoteRepository.class),
                CLOCK
        );
        var quote = new ExchangeService.Quote(
                CurrencyCode.PLN,
                CurrencyCode.EUR,
                new BigDecimal("100.00"),
                new BigDecimal("22.99"),
                new BigDecimal("0.22990000"),
                Instant.now(CLOCK)
        );

        var result = service.execute("client@example.pl", quote, "exchange-1");

        verify(account).debit(new BigDecimal("100.00"));
        verify(target).credit(new BigDecimal("22.99"));
        assertThat(result.targetCurrency()).isEqualTo(CurrencyCode.EUR);
    }

    private static ExchangeService service(ExchangeRateRepository rates) {
        return new ExchangeService(
                mock(AccountRepository.class),
                mock(CurrencyBalanceRepository.class),
                rates,
                mock(ExchangeTransactionRepository.class),
                mock(ExchangeQuoteRepository.class),
                CLOCK
        );
    }

    @Test
    void duplicateFoundAfterAccountLockDoesNotDebitAgain() {
        var fixture = fixture();
        var quote = quoteNow();
        var existing = new ExchangeTransaction(fixture.account(), quote.sourceCurrency(), quote.targetCurrency(),
                quote.sourceAmount(), quote.targetAmount(), quote.appliedRate(), "same-key");
        when(fixture.transactions().findByIdempotencyKey("same-key"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        assertThat(fixture.service().execute("client@example.pl", quote, "same-key").sourceAmount())
                .isEqualByComparingTo("100.00");
        verify(fixture.account(), never()).debit(any());
        verify(fixture.transactions(), never()).saveAndFlush(any());
    }

    @Test
    void preparedExecutionUsesStoredQuoteAndRejectsDifferentOwner() {
        var fixture = fixture();
        when(fixture.quotes().findById("server-key"))
                .thenReturn(Optional.of(new ExchangeQuote("server-key", "client@example.pl", quoteNow())));
        var result = fixture.service().executePrepared("client@example.pl", "server-key");
        assertThat(result.idempotencyKey()).isEqualTo("server-key");
        verify(fixture.account()).debit(new BigDecimal("100.00"));
        assertThatThrownBy(() -> fixture.service().executePrepared("other@example.pl", "server-key"))
                .isInstanceOf(ExchangeException.class);
        verify(fixture.transactions(), times(1)).saveAndFlush(any());
    }

    @Test
    void completedExchangeRemainsReplayableAfterQuoteCleanupAndExpiry() {
        var fixture = fixture();
        var quote = quoteNow();
        var existing = new ExchangeTransaction(fixture.account(), quote.sourceCurrency(), quote.targetCurrency(),
                quote.sourceAmount(), quote.targetAmount(), quote.appliedRate(), "completed-key");
        when(fixture.transactions().findByIdempotencyKey("completed-key")).thenReturn(Optional.of(existing));
        assertThat(fixture.service().executePrepared("client@example.pl", "completed-key").targetAmount())
                .isEqualByComparingTo("25.00");
        verifyNoInteractions(fixture.quotes());
        verify(fixture.accounts(), never()).findByOwnerEmailForUpdate(any());
        verify(fixture.account(), never()).debit(any());
        assertThatThrownBy(() -> fixture.service().executePrepared("other@example.pl", "completed-key"))
                .isInstanceOf(ExchangeException.class);
    }

    @Test
    void rejectsExpiredQuoteAndMissingFundsBeforeBalanceChanges() {
        var fixture = fixture();
        var expired = new ExchangeService.Quote(CurrencyCode.PLN, CurrencyCode.EUR,
                new BigDecimal("100.00"), new BigDecimal("25.00"), new BigDecimal("0.25000000"),
                Instant.now(CLOCK).minusSeconds(601));
        assertThatThrownBy(() -> fixture.service().execute("client@example.pl", expired, "expired-key"))
                .isInstanceOf(ExchangeException.class).hasMessageContaining("wygasła");
        when(fixture.account().getBalance()).thenReturn(new BigDecimal("10.00"));
        assertThatThrownBy(() -> fixture.service().execute("client@example.pl", quoteNow(), "no-funds"))
                .isInstanceOf(ExchangeException.class).hasMessageContaining("środków");
        verify(fixture.account(), never()).debit(any());
        verify(fixture.transactions(), never()).saveAndFlush(any());
    }

    @Test
    void persistsSeparateServerIssuedKeysForTwoQuotes() {
        var fixture = fixture();
        when(fixture.rates().findById(CurrencyCode.PLN))
                .thenReturn(Optional.of(new ExchangeRate(CurrencyCode.PLN, BigDecimal.ONE)));
        when(fixture.rates().findById(CurrencyCode.EUR))
                .thenReturn(Optional.of(new ExchangeRate(CurrencyCode.EUR, new BigDecimal("4.00"))));
        var a = fixture.service().prepareQuote("client@example.pl", CurrencyCode.PLN, CurrencyCode.EUR,
                new BigDecimal("100.00"));
        var b = fixture.service().prepareQuote("client@example.pl", CurrencyCode.PLN, CurrencyCode.EUR,
                new BigDecimal("200.00"));
        assertThat(a.idempotencyKey()).isNotEqualTo(b.idempotencyKey());
        var captor = org.mockito.ArgumentCaptor.forClass(ExchangeQuote.class);
        verify(fixture.quotes(), times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues().get(0).toQuote().sourceAmount()).isEqualByComparingTo("100.00");
        assertThat(captor.getAllValues().get(1).toQuote().sourceAmount()).isEqualByComparingTo("200.00");
        assertThat(captor.getAllValues().get(0).getIdempotencyKey()).isEqualTo(a.idempotencyKey());
    }

    @Test
    void rejectsRoundingToZeroBeforePersistingQuote() {
        var fixture = fixture();
        when(fixture.rates().findById(CurrencyCode.PLN))
                .thenReturn(Optional.of(new ExchangeRate(CurrencyCode.PLN, BigDecimal.ONE)));
        when(fixture.rates().findById(CurrencyCode.EUR))
                .thenReturn(Optional.of(new ExchangeRate(CurrencyCode.EUR, new BigDecimal("100.00"))));
        assertThatThrownBy(() -> fixture.service().prepareQuote("client@example.pl", CurrencyCode.PLN,
                CurrencyCode.EUR, new BigDecimal("0.01"))).isInstanceOf(ExchangeException.class);
        verify(fixture.quotes(), never()).saveAndFlush(any());
    }

    private static ExchangeService.Quote quoteNow() {
        return new ExchangeService.Quote(CurrencyCode.PLN, CurrencyCode.EUR, new BigDecimal("100.00"),
                new BigDecimal("25.00"), new BigDecimal("0.25000000"), Instant.now(CLOCK));
    }

    private record Fixture(ExchangeService service, Account account, AccountRepository accounts,
                           ExchangeQuoteRepository quotes, ExchangeTransactionRepository transactions,
                           ExchangeRateRepository rates) {}

    private static Fixture fixture() {
        var account = mock(Account.class);
        when(account.getOwner()).thenReturn(new User("client@example.pl", "hash", "Jan", "Kowalski", null));
        when(account.getId()).thenReturn(12L);
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(account.getBalance()).thenReturn(new BigDecimal("1000.00"));
        var accounts = mock(AccountRepository.class);
        when(accounts.findByOwnerEmailForUpdate("client@example.pl")).thenReturn(Optional.of(account));
        var balances = mock(CurrencyBalanceRepository.class);
        when(balances.findByAccountIdAndCurrencyForUpdate(12L, CurrencyCode.EUR))
                .thenReturn(Optional.of(mock(CurrencyBalance.class)));
        var transactions = mock(ExchangeTransactionRepository.class);
        when(transactions.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var quotes = mock(ExchangeQuoteRepository.class);
        var rates = mock(ExchangeRateRepository.class);
        return new Fixture(new ExchangeService(accounts, balances, rates, transactions, quotes, CLOCK),
                account, accounts, quotes, transactions, rates);
    }
}
