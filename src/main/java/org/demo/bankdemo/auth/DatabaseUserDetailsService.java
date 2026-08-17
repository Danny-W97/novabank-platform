package org.demo.bankdemo.auth;

import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.account.AccountStatus;
import org.demo.bankdemo.user.UserRepository;
import org.demo.bankdemo.user.UserStatus;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private static final String GENERIC_LOGIN_ERROR = "Nieprawidłowy login lub hasło.";

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final LoginAttemptService loginAttemptService;

    public DatabaseUserDetailsService(
            UserRepository userRepository,
            AccountRepository accountRepository,
            LoginAttemptService loginAttemptService
    ) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var normalizedEmail = RegistrationService.normalizeEmail(username);
        if (loginAttemptService.isBlocked(normalizedEmail)) {
            throw new LockedException(GENERIC_LOGIN_ERROR);
        }
        var bankUser = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new UsernameNotFoundException(GENERIC_LOGIN_ERROR));
        var accountIsActive = accountRepository.findByOwnerEmail(bankUser.getEmail())
                .map(account -> account.getStatus() == AccountStatus.ACTIVE)
                .orElse(true);

        return User.withUsername(bankUser.getEmail())
                .password(bankUser.getPasswordHash())
                .roles(bankUser.getRole().name())
                .disabled(bankUser.getStatus() != UserStatus.ACTIVE
                        || !accountIsActive
                        || !bankUser.isEmailVerified())
                .build();
    }
}
