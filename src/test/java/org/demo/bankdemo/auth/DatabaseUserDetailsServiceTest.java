package org.demo.bankdemo.auth;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.user.User;
import org.demo.bankdemo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.LockedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseUserDetailsServiceTest {

    @Test
    void loadsActiveUserByNormalizedEmail() {
        var userRepository = mock(UserRepository.class);
        var accountRepository = mock(AccountRepository.class);
        var loginAttemptService = mock(LoginAttemptService.class);
        var bankUser = new User(
                "anna.nowak@example.pl",
                "bcrypt-hash",
                "Anna",
                "Nowak",
                null
        );
        bankUser.verifyEmail(Instant.now());
        when(userRepository.findByEmail("anna.nowak@example.pl"))
                .thenReturn(Optional.of(bankUser));
        when(accountRepository.findByOwnerEmail("anna.nowak@example.pl"))
                .thenReturn(Optional.of(new Account(
                        bankUser,
                        "00123456789012345678901234",
                        BigDecimal.ZERO
                )));
        var service = new DatabaseUserDetailsService(
                userRepository,
                accountRepository,
                loginAttemptService
        );

        var userDetails = service.loadUserByUsername("  ANNA.NOWAK@EXAMPLE.PL ");

        assertThat(userDetails.getUsername()).isEqualTo("anna.nowak@example.pl");
        assertThat(userDetails.getPassword()).isEqualTo("bcrypt-hash");
        assertThat(userDetails.isEnabled()).isTrue();
        assertThat(userDetails.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_USER");
        verify(userRepository).findByEmail("anna.nowak@example.pl");
    }

    @Test
    void rejectsBlockedEmailBeforeDatabaseLookup() {
        var userRepository = mock(UserRepository.class);
        var accountRepository = mock(AccountRepository.class);
        var loginAttemptService = mock(LoginAttemptService.class);
        when(loginAttemptService.isBlocked("anna.nowak@example.pl")).thenReturn(true);
        var service = new DatabaseUserDetailsService(
                userRepository,
                accountRepository,
                loginAttemptService
        );

        assertThatThrownBy(() -> service.loadUserByUsername("ANNA.NOWAK@EXAMPLE.PL"))
                .isInstanceOf(LockedException.class);

        verify(userRepository, never()).findByEmail("anna.nowak@example.pl");
    }

    @Test
    void disablesLoginWhenAccountIsBlocked() {
        var userRepository = mock(UserRepository.class);
        var accountRepository = mock(AccountRepository.class);
        var loginAttemptService = mock(LoginAttemptService.class);
        var bankUser = new User(
                "anna.nowak@example.pl",
                "bcrypt-hash",
                "Anna",
                "Nowak",
                null
        );
        var account = new Account(bankUser, "00123456789012345678901234", BigDecimal.ZERO);
        account.block();
        when(userRepository.findByEmail("anna.nowak@example.pl"))
                .thenReturn(Optional.of(bankUser));
        when(accountRepository.findByOwnerEmail("anna.nowak@example.pl"))
                .thenReturn(Optional.of(account));

        var service = new DatabaseUserDetailsService(
                userRepository,
                accountRepository,
                loginAttemptService
        );

        var userDetails = service.loadUserByUsername("anna.nowak@example.pl");

        assertThat(userDetails.isEnabled()).isFalse();
    }
}
