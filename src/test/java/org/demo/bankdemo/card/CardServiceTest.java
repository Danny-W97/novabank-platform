package org.demo.bankdemo.card;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.account.AccountStatus;
import org.demo.bankdemo.currency.CurrencyBalanceRepository;
import org.demo.bankdemo.currency.CurrencyCode;
import org.demo.bankdemo.user.User;
import org.demo.bankdemo.transaction.TransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.time.Clock;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        var rates = mock(org.demo.bankdemo.currency.ExchangeRateRepository.class);
        var eur = mock(org.demo.bankdemo.currency.ExchangeRate.class);
        when(eur.getRateToPln()).thenReturn(new BigDecimal("4.25"));
        when(rates.findById(CurrencyCode.EUR)).thenReturn(Optional.of(eur));
        var service = new CardPaymentService(accounts, balances, cards, transactions, Clock.systemUTC(), rates);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", mock(jakarta.persistence.EntityManager.class));

        var result = service.pay(
                "client@example.pl", "NovaTech", CurrencyCode.EUR,
                new BigDecimal("20.00"), CardPaymentChannel.IN_STORE, "card-1"
        );

        verify(balance).debit(new BigDecimal("20.00"));
        verify(account, never()).debit(any());
        assertThat(result.status()).isEqualTo(CardTransactionStatus.COMPLETED);
    }

    @Test
    void onlineReplayRequiresSameMerchantAndReturnsOriginalReference() {
        var cards = mock(DemoCardRepository.class);
        var transactions = mock(CardTransactionRepository.class);
        var card = mock(DemoCard.class);
        var account = mock(Account.class);
        var previous = mock(CardTransaction.class);
        when(previous.getCard()).thenReturn(card);
        when(card.getAccount()).thenReturn(account);
        when(account.getAccountNumber()).thenReturn("11111111111111111111111111");
        when(card.getOnlineNumber()).thenReturn("1234567890123456");
        when(card.getDemoCvv()).thenReturn("123");
        when(card.getDemoExpiresAt()).thenReturn(LocalDate.of(2029, 12, 1));
        when(previous.getCurrency()).thenReturn(CurrencyCode.PLN);
        when(previous.getAmount()).thenReturn(new BigDecimal("25.00"));
        when(previous.getAmountInPln()).thenReturn(new BigDecimal("25.00"));
        when(previous.getMerchantAccountNumber()).thenReturn("22222222222222222222222222");
        when(previous.getOrderId()).thenReturn("order-1");
        when(previous.getPaymentTitle()).thenReturn("Zakup");
        when(previous.getBankReference()).thenReturn("CARD-REF-1");
        when(previous.getStatus()).thenReturn(CardTransactionStatus.COMPLETED);
        when(transactions.findByIdempotencyKey("card-key"))
                .thenReturn(Optional.of(previous));
        var service = new OnlineCardPaymentService(mock(AccountRepository.class),
                mock(CurrencyBalanceRepository.class), cards, transactions, Clock.systemUTC(),
                mock(TransactionRepository.class), mock(org.demo.bankdemo.currency.ExchangeRateRepository.class));

        assertThatThrownBy(() -> service.payOnline("1234567890123456", "123", "12/29",
                "33333333333333333333333333", "order-1", CurrencyCode.PLN,
                new BigDecimal("25.00"), new BigDecimal("25.00"), "Zakup", "card-key", "merchant"))
                .isInstanceOf(CardException.class);

        var replay = service.payOnline("1234567890123456", "123", "12/29",
                "22222222222222222222222222", "order-1", CurrencyCode.PLN,
                new BigDecimal("25.00"), new BigDecimal("25.00"), "Zakup", "card-key", "merchant");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.referenceNumber()).isEqualTo("CARD-REF-1");
    }
}
