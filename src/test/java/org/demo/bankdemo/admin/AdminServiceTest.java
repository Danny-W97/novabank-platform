package org.demo.bankdemo.admin;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.account.AccountStatus;
import org.demo.bankdemo.audit.OperationAuditService;
import org.demo.bankdemo.profile.UserSessionService;
import org.demo.bankdemo.transaction.TransactionRepository;
import org.demo.bankdemo.user.User;
import org.demo.bankdemo.user.UserRepository;
import org.demo.bankdemo.user.UserStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminServiceTest {

    @Test
    void blockingAccountAlsoBlocksOwnerAndInvalidatesSessions() {
        var user = new User("client@example.pl", "hash", "Jan", "Kowalski", null);
        var account = new Account(user, "00123456789012345678901234", BigDecimal.ZERO);
        var accountRepository = mock(AccountRepository.class);
        var sessionService = mock(UserSessionService.class);
        when(accountRepository.findByIdWithOwner(1L)).thenReturn(Optional.of(account));
        var service = service(accountRepository, sessionService);

        service.blockAccount(1L, "admin@example.pl");

        assertThat(account.getStatus()).isEqualTo(AccountStatus.BLOCKED);
        assertThat(user.getStatus()).isEqualTo(UserStatus.BLOCKED);
        verify(sessionService).invalidateAllSessions("client@example.pl");
    }

    @Test
    void administratorCannotBlockOwnAccount() {
        var user = new User("admin@example.pl", "hash", "Admin", "Demo", null);
        var account = new Account(user, "00123456789012345678901234", BigDecimal.ZERO);
        var accountRepository = mock(AccountRepository.class);
        when(accountRepository.findByIdWithOwner(1L)).thenReturn(Optional.of(account));
        var service = service(accountRepository, mock(UserSessionService.class));

        assertThatThrownBy(() -> service.blockAccount(1L, "ADMIN@EXAMPLE.PL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("własnego");
    }

    @Test
    void closingAccountKeepsAccountDataAndBlocksOwner() {
        var user = new User("client@example.pl", "hash", "Jan", "Kowalski", null);
        var account = new Account(user, "00123456789012345678901234", BigDecimal.valueOf(125));
        var accountRepository = mock(AccountRepository.class);
        var sessionService = mock(UserSessionService.class);
        when(accountRepository.findByIdWithOwner(1L)).thenReturn(Optional.of(account));
        var service = service(accountRepository, sessionService);

        service.closeAccount(1L, "admin@example.pl");

        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(account.getBalance()).isEqualByComparingTo("125.00");
        assertThat(user.getStatus()).isEqualTo(UserStatus.BLOCKED);
        verify(sessionService).invalidateAllSessions("client@example.pl");
    }

    @Test
    void blockingAccountRecordsStatusHistoryAndAdminAudit() {
        var user = new User("client@example.pl", "hash", "Jan", "Kowalski", null);
        var account = new Account(user, "00123456789012345678901234", BigDecimal.ZERO);
        var accountRepository = mock(AccountRepository.class);
        var sessionService = mock(UserSessionService.class);
        var auditService = mock(OperationAuditService.class);
        var statusRepository = mock(AdminStatusChangeRepository.class);
        when(accountRepository.findByIdWithOwner(1L)).thenReturn(Optional.of(account));
        var service = new AdminService(
                mock(UserRepository.class), accountRepository, mock(TransactionRepository.class),
                auditService, sessionService, statusRepository
        );

        service.blockAccount(1L, "admin@example.pl", "127.0.0.1");

        verify(statusRepository).save(any(AdminStatusChange.class));
        verify(auditService).recordAdminAction(
                "admin@example.pl", "ADMIN_ACCOUNT_BLOCK", "client@example.pl", null,
                "Zablokowano rachunek i konto uzytkownika.", true, "127.0.0.1"
        );
    }

    private static AdminService service(AccountRepository accountRepository, UserSessionService sessionService) {
        return new AdminService(
                mock(UserRepository.class),
                accountRepository,
                mock(TransactionRepository.class),
                mock(OperationAuditService.class),
                sessionService
        );
    }
}
