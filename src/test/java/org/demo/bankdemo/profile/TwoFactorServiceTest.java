package org.demo.bankdemo.profile;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import jakarta.servlet.http.HttpSession;
import org.demo.bankdemo.security.TotpSecretCipher;
import org.demo.bankdemo.user.User;
import org.demo.bankdemo.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TwoFactorServiceTest {

    private static final String EMAIL = "anna.nowak@example.pl";
    private static final String ENCRYPTION_KEY = "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY=";

    private UserRepository userRepository;
    private TotpRecoveryCodeRepository recoveryCodeRepository;
    private User user;
    private TwoFactorService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        recoveryCodeRepository = mock(TotpRecoveryCodeRepository.class);
        user = new User(EMAIL, new BCryptPasswordEncoder().encode("Password1"), "Anna", "Nowak", null);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        var cipher = new TotpSecretCipher(ENCRYPTION_KEY);
        service = new TwoFactorService(
                userRepository,
                recoveryCodeRepository,
                new BCryptPasswordEncoder(),
                cipher,
                Clock.fixed(Instant.parse("2026-08-02T21:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void activatesTotpAfterValidCodeAndCreatesRecoveryCodes() throws Exception {
        var session = new MockHttpSession();
        var setup = service.setupFor(EMAIL, session);
        var code = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6)
                .generate(setup.manualKey(), Instant.now().getEpochSecond() / 30);

        var recoveryCodes = service.confirmSetup(EMAIL, code, session);

        assertThat(user.isTotpEnabled()).isTrue();
        assertThat(user.getTotpSecretEncrypted()).isNotEqualTo(setup.manualKey());
        assertThat(recoveryCodes).hasSize(10);
        assertThat(session.getAttribute(TwoFactorService.SETUP_SECRET_ATTRIBUTE)).isNull();
    }

    @Test
    void acceptsCurrentTotpCodeForLogin() throws Exception {
        var session = new MockHttpSession();
        var setup = service.setupFor(EMAIL, session);
        var code = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6)
                .generate(setup.manualKey(), Instant.now().getEpochSecond() / 30);
        service.confirmSetup(EMAIL, code, session);

        var loginCode = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6)
                .generate(setup.manualKey(), Instant.now().getEpochSecond() / 30);

        assertThat(service.verifyLoginCode(EMAIL, loginCode)).isTrue();
    }
}
