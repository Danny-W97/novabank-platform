package org.demo.bankdemo.auth;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountNumberGenerator;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.config.RegistrationProperties;
import org.demo.bankdemo.transaction.BankTransaction;
import org.demo.bankdemo.transaction.TransactionRepository;
import org.demo.bankdemo.user.User;
import org.demo.bankdemo.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.Locale;
import java.util.UUID;

@Service
public class RegistrationService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final PasswordEncoder passwordEncoder;
    private final RegistrationProperties registrationProperties;
    private final EmailTokenService emailTokenService;

    public RegistrationService(
            UserRepository userRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            AccountNumberGenerator accountNumberGenerator,
            PasswordEncoder passwordEncoder,
            RegistrationProperties registrationProperties,
            EmailTokenService emailTokenService
    ) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.accountNumberGenerator = accountNumberGenerator;
        this.passwordEncoder = passwordEncoder;
        this.registrationProperties = registrationProperties;
        this.emailTokenService = emailTokenService;
    }

    @Transactional(noRollbackFor = EmailDeliveryException.class)
    public RegistrationResult register(RegistrationForm form) {
        if (!form.passwordsMatch()) {
            throw new IllegalArgumentException("Hasła nie są zgodne.");
        }

        var normalizedEmail = normalizeEmail(form.getEmail());
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateEmailException();
        }

        var user = new User(
                normalizedEmail,
                passwordEncoder.encode(form.getPassword()),
                normalizeRequiredText(form.getFirstName()),
                normalizeRequiredText(form.getLastName()),
                normalizeOptionalText(form.getPhone())
        );

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateEmailException();
        }

        var initialBalance = registrationProperties.getInitialBalance()
                .setScale(2, RoundingMode.UNNECESSARY);
        var account = accountRepository.save(
                new Account(user, accountNumberGenerator.generateUnique(), initialBalance)
        );

        if (initialBalance.signum() > 0) {
            transactionRepository.save(BankTransaction.initialCredit(
                    UUID.randomUUID().toString().replace("-", ""),
                    "registration:" + UUID.randomUUID(),
                    account,
                    initialBalance
            ));
        }

        emailTokenService.issueVerification(user);

        return new RegistrationResult(user.getEmail(), account.getAccountNumber());
    }

    static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizeRequiredText(String value) {
        return value.strip();
    }

    private static String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    public record RegistrationResult(String email, String accountNumber) {
    }
}
