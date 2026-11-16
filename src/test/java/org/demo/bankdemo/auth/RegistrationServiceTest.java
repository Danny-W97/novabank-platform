package org.demo.bankdemo.auth;

import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountNumberGenerator;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.config.RegistrationProperties;
import org.demo.bankdemo.transaction.BankTransaction;
import org.demo.bankdemo.transaction.TransactionRepository;
import org.demo.bankdemo.user.User;
import org.demo.bankdemo.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    private static final String ACCOUNT_NUMBER = "61109010140000071219812874";

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountNumberGenerator accountNumberGenerator;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailTokenService emailTokenService;

    private RegistrationService registrationService;

    @BeforeEach
    void setUp() {
        var properties = new RegistrationProperties();
        properties.setInitialBalance(new BigDecimal("10000.00"));
        registrationService = new RegistrationService(
                userRepository,
                accountRepository,
                transactionRepository,
                accountNumberGenerator,
                passwordEncoder,
                properties,
                emailTokenService
        );
    }

    @Test
    void registersUserAccountAndInitialCreditAtomically() {
        var form = validForm();
        when(userRepository.existsByEmail("anna.nowak@example.pl")).thenReturn(false);
        when(passwordEncoder.encode("Bezpieczne1")).thenReturn("bcrypt-hash");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(accountNumberGenerator.generateUnique()).thenReturn(ACCOUNT_NUMBER);
        when(accountRepository.save(any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = registrationService.register(form);

        var userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("anna.nowak@example.pl");
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("bcrypt-hash");
        assertThat(userCaptor.getValue().getPhone()).isNull();

        var accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getAccountNumber()).isEqualTo(ACCOUNT_NUMBER);
        assertThat(accountCaptor.getValue().getBalance()).isEqualByComparingTo("10000.00");

        verify(transactionRepository).save(any(BankTransaction.class));
        assertThat(result.email()).isEqualTo("anna.nowak@example.pl");
        assertThat(result.accountNumber()).isEqualTo(ACCOUNT_NUMBER);
    }

    @Test
    void rejectsDuplicateNormalizedEmailBeforeCreatingAccount() {
        var form = validForm();
        form.setEmail("  ANNA.NOWAK@EXAMPLE.PL ");
        when(userRepository.existsByEmail("anna.nowak@example.pl")).thenReturn(true);

        assertThatThrownBy(() -> registrationService.register(form))
                .isInstanceOf(DuplicateEmailException.class);

        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(accountRepository, never()).save(any(Account.class));
        verify(transactionRepository, never()).save(any(BankTransaction.class));
    }

    @Test
    void rejectsMismatchedPasswords() {
        var form = validForm();
        form.setPasswordConfirmation("InneHaslo1");

        assertThatThrownBy(() -> registrationService.register(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Hasła nie są zgodne.");

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    private static RegistrationForm validForm() {
        var form = new RegistrationForm();
        form.setFirstName(" Anna ");
        form.setLastName(" Nowak ");
        form.setEmail("Anna.Nowak@Example.pl");
        form.setPhone(" ");
        form.setPassword("Bezpieczne1");
        form.setPasswordConfirmation("Bezpieczne1");
        form.setTermsAccepted(true);
        return form;
    }
}
