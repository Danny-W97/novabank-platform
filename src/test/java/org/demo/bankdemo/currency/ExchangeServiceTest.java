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
                CLOCK
        );
    }
}
