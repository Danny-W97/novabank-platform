package org.demo.bankdemo.card;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.account.AccountStatus;
import org.demo.bankdemo.currency.CurrencyBalanceRepository;
import org.demo.bankdemo.currency.CurrencyCode;
import org.demo.bankdemo.user.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CardServiceTest {

    @Test
    void usesMatchingForeignCurrencySubaccount() {
        var user = new User("client@example.pl", "hash", "Jan", "Kowalski", null);
        var account = mock(Account.class);
        when(account.getId()).thenReturn(7L);
        when(account.getOwner()).thenReturn(user);
        when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
        var card = mock(DemoCard.class);
        when(card.getAccount()).thenReturn(account);
        when(card.getStatus()).thenReturn(CardStatus.ACTIVE);
        when(card.statusAt(any())).thenReturn(CardStatus.ACTIVE);
        var balance = mock(org.demo.bankdemo.currency.CurrencyBalance.class);
        var accounts = mock(AccountRepository.class);
        var balances = mock(CurrencyBalanceRepository.class);
        var cards = mock(DemoCardRepository.class);
        var transactions = mock(CardTransactionRepository.class);
        when(accounts.findByOwnerEmailForUpdate("client@example.pl")).thenReturn(Optional.of(account));
        when(cards.findByAccountId(7L)).thenReturn(Optional.of(card));
        when(balances.findByAccountIdAndCurrencyForUpdate(7L, CurrencyCode.EUR))
                .thenReturn(Optional.of(balance));
        var stored = mock(CardTransaction.class);
        when(stored.getCard()).thenReturn(card);
        when(stored.getCurrency()).thenReturn(CurrencyCode.EUR);
        when(stored.getAmount()).thenReturn(new BigDecimal("20.00"));
        when(stored.getStatus()).thenReturn(CardTransactionStatus.COMPLETED);
        when(transactions.findByIdempotencyKey("card-1")).thenReturn(Optional.empty());
        when(transactions.saveAndFlush(any())).thenReturn(stored);
        var service = new CardService(accounts, balances, cards, transactions);

        var result = service.pay(
                "client@example.pl", "NovaTech", CurrencyCode.EUR,
                new BigDecimal("20.00"), "card-1"
        );

        verify(balance).debit(new BigDecimal("20.00"));
        verify(account, never()).debit(any());
        assertThat(result.status()).isEqualTo(CardTransactionStatus.COMPLETED);
    }
}
