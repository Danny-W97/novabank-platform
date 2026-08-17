package org.demo.bankdemo.payment;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.account.AccountStatus;
import org.demo.bankdemo.notification.NotificationService;
import org.demo.bankdemo.transaction.BankTransaction;
import org.demo.bankdemo.transaction.TransactionRepository;
import org.demo.bankdemo.transfer.TransactionReferenceGenerator;
import org.demo.bankdemo.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private static final String KEY = "shop-order-123-payment-1";
    private static final String CUSTOMER_ACCOUNT = "11111111111111111111111111";
    private static final String MERCHANT_ACCOUNT = "22222222222222222222222222";

    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private Account customerAccount;
    private Account merchantAccount;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        var referenceGenerator = mock(TransactionReferenceGenerator.class);
        var notificationService = mock(NotificationService.class);

        customerAccount = mock(Account.class);
        merchantAccount = mock(Account.class);
        var customer = new User("customer@example.com", "hash", "Jan", "Kowalski", null);
        var merchant = new User("shop@example.com", "hash", "Sklep", "Demo", null);

        when(customerAccount.getId()).thenReturn(1L);
        when(customerAccount.getAccountNumber()).thenReturn(CUSTOMER_ACCOUNT);
        when(customerAccount.getOwner()).thenReturn(customer);
        when(customerAccount.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(customerAccount.getCurrency()).thenReturn("PLN");
        when(customerAccount.getBalance()).thenReturn(new BigDecimal("100.00"));

        when(merchantAccount.getId()).thenReturn(2L);
        when(merchantAccount.getAccountNumber()).thenReturn(MERCHANT_ACCOUNT);
        when(merchantAccount.getOwner()).thenReturn(merchant);
        when(merchantAccount.getStatus()).thenReturn(AccountStatus.ACTIVE);
        when(merchantAccount.getCurrency()).thenReturn("PLN");

        when(accountRepository.findByAccountNumber(CUSTOMER_ACCOUNT))
                .thenReturn(Optional.of(customerAccount));
        when(accountRepository.findByAccountNumber(MERCHANT_ACCOUNT))
                .thenReturn(Optional.of(merchantAccount));
        when(accountRepository.findAllByIdForUpdate(List.of(1L, 2L)))
                .thenReturn(List.of(customerAccount, merchantAccount));
        when(referenceGenerator.generate()).thenReturn("TRX-PAYMENT-123");

        paymentService = new PaymentService(
                accountRepository,
                transactionRepository,
                referenceGenerator,
                notificationService,
                Clock.fixed(Instant.parse("2026-07-29T12:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void retriesTheSamePaymentWithoutDebitingTheAccountTwice() {
        var request = new PaymentRequest(
                CUSTOMER_ACCOUNT,
                MERCHANT_ACCOUNT,
                "order-123",
                new BigDecimal("25.00"),
                "PLN",
                "Sklep Demo - order-123"
        );
        when(transactionRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());

        var first = paymentService.execute(KEY, request);
        var savedTransaction = BankTransaction.payment(
                first.referenceNumber(),
                KEY,
                request.orderId(),
                customerAccount,
                merchantAccount,
                customerAccount.getOwner().getFullName(),
                merchantAccount.getOwner().getFullName(),
                request.amount(),
                request.title(),
                Instant.parse("2026-07-29T12:00:00Z")
        );
        when(transactionRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.of(savedTransaction));

        var second = paymentService.execute(KEY, request);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.referenceNumber()).isEqualTo(first.referenceNumber());
        verify(customerAccount, times(1)).debit(request.amount());
        verify(merchantAccount, times(1)).credit(request.amount());
        verify(transactionRepository, times(1)).saveAndFlush(any(BankTransaction.class));
    }
}
