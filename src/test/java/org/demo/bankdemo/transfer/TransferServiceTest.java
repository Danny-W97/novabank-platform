package org.demo.bankdemo.transfer;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.config.TransferLimitsProperties;
import org.demo.bankdemo.notification.NotificationService;
import org.demo.bankdemo.transaction.BankTransaction;
import org.demo.bankdemo.transaction.TransactionRepository;
import org.demo.bankdemo.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final String SOURCE_NUMBER = "61109010140000071219812874";
    private static final String DESTINATION_NUMBER = "12127943138124663147042900";
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";
    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionReferenceGenerator referenceGenerator;

    @Mock
    private NotificationService notificationService;

    @Mock
    private TransferRateLimitService rateLimitService;

    private TransferService transferService;
    private Account source;
    private Account destination;

    @BeforeEach
    void setUp() {
        var limits = new TransferLimitsProperties();
        transferService = new TransferService(
                accountRepository,
                transactionRepository,
                referenceGenerator,
                notificationService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                limits,
                rateLimitService
        );
        lenient().when(rateLimitService.tryAcquire(any(), any(Integer.class))).thenReturn(true);
        lenient().when(transactionRepository.sumOutgoingByEmailSince(any(), any(), any()))
                .thenReturn(BigDecimal.ZERO);
        source = account(
                9L,
                new User("sender@example.pl", "hash", "Jan", "Kowalski", null),
                SOURCE_NUMBER,
                "1000.00"
        );
        destination = account(
                3L,
                new User("recipient@example.pl", "hash", "Anna", "Nowak", null),
                DESTINATION_NUMBER,
                "100.00"
        );
    }

    @Test
    void executesTransferAtomicallyAfterLockingAccountsInAscendingOrder() {
        var command = command("125.50");
        when(transactionRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty(), Optional.empty());
        when(accountRepository.findIdByOwnerEmail("sender@example.pl"))
                .thenReturn(Optional.of(source.getId()));
        when(accountRepository.findIdByAccountNumber(DESTINATION_NUMBER))
                .thenReturn(Optional.of(destination.getId()));
        when(accountRepository.findAllByIdForUpdate(List.of(3L, 9L)))
                .thenReturn(List.of(destination, source));
        when(referenceGenerator.generate()).thenReturn("TRX-1234567890123456789012345678");
        when(transactionRepository.saveAndFlush(any(BankTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = transferService.execute(" SENDER@EXAMPLE.PL ", command);

        assertThat(source.getBalance()).isEqualByComparingTo("874.50");
        assertThat(destination.getBalance()).isEqualByComparingTo("225.50");
        assertThat(result.referenceNumber()).isEqualTo("TRX-1234567890123456789012345678");
        assertThat(result.amount()).isEqualTo("125,50 zł");
        verify(accountRepository).findAllByIdForUpdate(List.of(3L, 9L));
        verify(notificationService).transferCompleted(
                source.getOwner(),
                destination.getOwner(),
                result.amount(),
                result.referenceNumber()
        );

        var transactionCaptor = ArgumentCaptor.forClass(BankTransaction.class);
        verify(transactionRepository).saveAndFlush(transactionCaptor.capture());
        assertThat(transactionCaptor.getValue().getSourceAccount()).isSameAs(source);
        assertThat(transactionCaptor.getValue().getDestinationAccount()).isSameAs(destination);
    }

    @Test
    void rejectsInsufficientFundsBeforeChangingBalances() {
        var command = command("1200.00");
        when(transactionRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(accountRepository.findIdByOwnerEmail("sender@example.pl"))
                .thenReturn(Optional.of(source.getId()));
        when(accountRepository.findIdByAccountNumber(DESTINATION_NUMBER))
                .thenReturn(Optional.of(destination.getId()));
        when(accountRepository.findAllByIdForUpdate(List.of(3L, 9L)))
                .thenReturn(List.of(destination, source));

        assertThatThrownBy(() -> transferService.execute("sender@example.pl", command))
                .isInstanceOf(TransferException.class)
                .hasMessage("Brak wystarczających środków na rachunku.");

        assertThat(source.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(destination.getBalance()).isEqualByComparingTo("100.00");
        verify(accountRepository).findAllByIdForUpdate(List.of(3L, 9L));
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsTransferToOwnAccount() {
        var command = new TransferCommand(
                KEY,
                "Jan Kowalski",
                SOURCE_NUMBER,
                new BigDecimal("10.00"),
                "Próba przelewu własnego"
        );
        when(transactionRepository.findByIdempotencyKey(KEY)).thenReturn(Optional.empty());
        when(accountRepository.findIdByOwnerEmail("sender@example.pl"))
                .thenReturn(Optional.of(source.getId()));
        when(accountRepository.findIdByAccountNumber(SOURCE_NUMBER))
                .thenReturn(Optional.of(source.getId()));

        assertThatThrownBy(() -> transferService.execute("sender@example.pl", command))
                .isInstanceOf(TransferException.class)
                .hasMessage("Nie można wykonać przelewu na własny rachunek.");

        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void repeatedRequestReturnsExistingTransferWithoutSecondDebit() {
        var command = command("125.50");
        var existing = existingTransaction(command);
        when(transactionRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.of(existing));

        var result = transferService.execute("sender@example.pl", command);

        assertThat(result.referenceNumber()).isEqualTo(existing.getReferenceNumber());
        assertThat(source.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(destination.getBalance()).isEqualByComparingTo("100.00");
        verify(accountRepository, never()).findAllByIdForUpdate(any());
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentDuplicateDetectedAfterLocksDoesNotDebitAgain() {
        var command = command("125.50");
        var existing = existingTransaction(command);
        when(transactionRepository.findByIdempotencyKey(KEY))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(accountRepository.findIdByOwnerEmail("sender@example.pl"))
                .thenReturn(Optional.of(source.getId()));
        when(accountRepository.findIdByAccountNumber(DESTINATION_NUMBER))
                .thenReturn(Optional.of(destination.getId()));
        when(accountRepository.findAllByIdForUpdate(List.of(3L, 9L)))
                .thenReturn(List.of(destination, source));

        transferService.execute("sender@example.pl", command);

        assertThat(source.getBalance()).isEqualByComparingTo("1000.00");
        assertThat(destination.getBalance()).isEqualByComparingTo("100.00");
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsInvalidNrbChecksumBeforeDatabaseLookup() {
        var command = new TransferCommand(
                KEY,
                "Anna Nowak",
                "00109010140000071219812887",
                new BigDecimal("10.00"),
                "Błędny rachunek"
        );

        assertThatThrownBy(() -> transferService.preview("sender@example.pl", command))
                .isInstanceOf(TransferException.class)
                .hasMessage("Podaj poprawny 26-cyfrowy numer rachunku NRB.");

        verify(accountRepository, never()).findByOwnerEmail(any());
    }

    private TransferCommand command(String amount) {
        return new TransferCommand(
                KEY,
                "Anna Nowak",
                DESTINATION_NUMBER,
                new BigDecimal(amount),
                "Zwrot za obiad"
        );
    }

    private BankTransaction existingTransaction(TransferCommand command) {
        return BankTransaction.transfer(
                "TRX-EXISTING123456789012345678",
                KEY,
                source,
                destination,
                source.getOwner().getFullName(),
                command.recipientName(),
                command.amount(),
                command.title(),
                NOW
        );
    }

    private static Account account(
            Long id,
            User owner,
            String accountNumber,
            String balance
    ) {
        var account = new Account(owner, accountNumber, new BigDecimal(balance));
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }
}
