package org.demo.bankdemo.auth;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.demo.bankdemo.account.Account;
import org.demo.bankdemo.account.AccountNumberGenerator;
import org.demo.bankdemo.account.AccountRepository;
import org.demo.bankdemo.audit.LoginHistoryService;
import org.demo.bankdemo.config.EmailProperties;
import org.demo.bankdemo.config.RegistrationProperties;
import org.demo.bankdemo.profile.TwoFactorService;
import org.demo.bankdemo.profile.UserSessionService;
import org.demo.bankdemo.transaction.TransactionRepository;
import org.demo.bankdemo.user.User;
import org.demo.bankdemo.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.ui.ConcurrentModel;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class AuthControllerEmailFlowTest {

    private final RegistrationService registrationService = mock(RegistrationService.class);
    private final TwoFactorService twoFactorService = mock(TwoFactorService.class);
    private final TwoFactorLoginService twoFactorLoginService = mock(TwoFactorLoginService.class);
    private final EmailTokenService emailTokenService = mock(EmailTokenService.class);
    private final LoginHistoryService loginHistoryService = mock(LoginHistoryService.class);

    @Test
    void verificationSentExposesMailpitUrlOnlyInIsolatedDevProfile() {
        var properties = new EmailProperties();
        properties.setMailpitUrl("http://localhost:18026");

        var devEnv = new MockEnvironment();
        devEnv.setActiveProfiles("dev");
        var devController = new AuthController(
                registrationService, twoFactorService, twoFactorLoginService,
                emailTokenService, loginHistoryService, properties, devEnv
        );
        var devModel = new ConcurrentModel();
        assertThat(devController.verificationSent("klient@example.pl", devModel)).isEqualTo("auth/check-email");
        assertThat(devModel.getAttribute("mailpitUrl")).isEqualTo("http://localhost:18026");

        var prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        var prodController = new AuthController(
                registrationService, twoFactorService, twoFactorLoginService,
                emailTokenService, loginHistoryService, properties, prodEnv
        );
        var prodModel = new ConcurrentModel();
        assertThat(prodController.verificationSent("klient@example.pl", prodModel)).isEqualTo("auth/check-email");
        assertThat(prodModel.containsAttribute("mailpitUrl")).isFalse();
    }

    @Test
    void registerAndResendSurfaceSmtpErrorInsteadOfClaimingDeliverySucceeded() {
        var controller = new AuthController(
                registrationService, twoFactorService, twoFactorLoginService,
                emailTokenService, loginHistoryService, new EmailProperties(), new MockEnvironment()
        );
        var form = new RegistrationForm();
        form.setFirstName("Jan");
        form.setLastName("Kowalski");
        form.setEmail("jan.kowalski@gmail.com");
        form.setPassword("BezpieczneHaslo1!");
        form.setPasswordConfirmation("BezpieczneHaslo1!");
        form.setTermsAccepted(true);

        doThrow(new EmailDeliveryException("SMTP down"))
                .when(registrationService).register(any(RegistrationForm.class));

        var binding = new BeanPropertyBindingResult(form, "registrationForm");
        var redirects = new RedirectAttributesModelMap();
        var view = controller.register(form, binding, redirects);

        assertThat(view).isEqualTo("redirect:/verify-email/sent?email=jan.kowalski%40gmail.com");
        assertThat(redirects.getFlashAttributes())
                .containsKey("verificationDeliveryError")
                .doesNotContainKey("verificationSent");

        doThrow(new EmailDeliveryException("SMTP down"))
                .when(emailTokenService).resendVerification("jan.kowalski@gmail.com");
        var resendRedirects = new RedirectAttributesModelMap();
        var resendView = controller.resendVerification("jan.kowalski@gmail.com", resendRedirects);
        assertThat(resendView).isEqualTo("redirect:/verify-email/sent?email=jan.kowalski%40gmail.com");
        assertThat(resendRedirects.getFlashAttributes())
                .containsKey("verificationDeliveryError")
                .doesNotContainKey("verificationSent");
    }

    @Test
    void checkEmailTemplateRemovesLogHintAndDisplaysMailpitAndDeliveryError() throws Exception {
        var template = Files.readString(
                Path.of("src/main/resources/templates/auth/check-email.html"),
                StandardCharsets.UTF_8
        );
        assertThat(template)
                .doesNotContain("w logu aplikacji")
                .contains("Aktywuj konto bankowe")
                .contains("24 godziny")
                .contains("mailpitUrl")
                .contains("verificationDeliveryError");
    }

    @Test
    void fullRegistrationVerificationResendExpiryReuseAndLoginBlockLifecycle(CapturedOutput output) throws Exception {
        var currentTime = new AtomicReference<>(Instant.parse("2026-10-31T12:00:00Z"));
        Clock mutableClock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return currentTime.get(); }
        };

        Map<String, User> usersByEmail = new HashMap<>();
        Map<String, EmailVerificationToken> tokensByHash = new HashMap<>();

        var userRepo = mock(UserRepository.class);
        when(userRepo.existsByEmail(anyString())).thenAnswer(inv -> usersByEmail.containsKey(inv.getArgument(0)));
        when(userRepo.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            usersByEmail.put(u.getEmail(), u);
            return u;
        });
        when(userRepo.findByEmail(anyString())).thenAnswer(inv -> Optional.ofNullable(usersByEmail.get(inv.getArgument(0))));

        var accountRepo = mock(AccountRepository.class);
        when(accountRepo.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepo.findByOwnerEmail(anyString())).thenReturn(Optional.empty());

        var verificationRepo = mock(EmailVerificationTokenRepository.class);
        doAnswer(inv -> {
            tokensByHash.clear();
            return null;
        }).when(verificationRepo).deleteAllByUserId(any());
        when(verificationRepo.save(any(EmailVerificationToken.class))).thenAnswer(inv -> {
            EmailVerificationToken t = inv.getArgument(0);
            String tokenHash = (String) org.springframework.test.util.ReflectionTestUtils.getField(t, "tokenHash");
            tokensByHash.put(tokenHash, t);
            return t;
        });
        when(verificationRepo.findByTokenHash(anyString())).thenAnswer(inv -> Optional.ofNullable(tokensByHash.get(inv.getArgument(0))));
        when(verificationRepo.consumeUnused(anyString(), any(Instant.class))).thenAnswer(inv -> {
            String hash = inv.getArgument(0);
            Instant now = inv.getArgument(1);
            EmailVerificationToken token = tokensByHash.get(hash);
            if (token == null || !token.isUsable(now)) {
                return 0;
            }
            token.markUsed(now);
            return 1;
        });

        var sender = mock(JavaMailSender.class);
        var lastMimeMessage = new AtomicReference<MimeMessage>();
        when(sender.createMimeMessage()).thenAnswer(inv -> {
            var msg = new MimeMessage(Session.getInstance(new Properties()));
            lastMimeMessage.set(msg);
            return msg;
        });
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);

        var emailProps = new EmailProperties();
        emailProps.setMode("smtp");
        emailProps.setBaseUrl("http://localhost:8080");
        var devEnv = new MockEnvironment();
        devEnv.setActiveProfiles("dev");

        var deliveryService = new EmailDeliveryService(emailProps, provider, devEnv);
        var encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("hashed-pw");

        var tokenService = new EmailTokenService(
                verificationRepo,
                mock(PasswordResetTokenRepository.class),
                userRepo,
                encoder,
                deliveryService,
                mock(UserSessionService.class),
                mutableClock
        );

        var regProps = new RegistrationProperties();
        regProps.setInitialBalance(new BigDecimal("1000.00"));
        var accountNumberGenerator = mock(AccountNumberGenerator.class);
        when(accountNumberGenerator.generateUnique()).thenReturn("61109010140000071219812874");

        var regService = new RegistrationService(
                userRepo,
                accountRepo,
                mock(TransactionRepository.class),
                accountNumberGenerator,
                encoder,
                regProps,
                tokenService
        );

        var loginAttemptService = mock(LoginAttemptService.class);
        when(loginAttemptService.isBlocked(anyString())).thenReturn(false);
        var userDetailsService = new DatabaseUserDetailsService(userRepo, accountRepo, loginAttemptService);

        // 1. Register user with Gmail address -> delivered to local Mailpit via SMTP
        var form = new RegistrationForm();
        form.setFirstName("Anna");
        form.setLastName("Nowak");
        form.setEmail("anna.nowak@gmail.com");
        form.setPassword("Bezpieczne1!");
        form.setPasswordConfirmation("Bezpieczne1!");
        form.setTermsAccepted(true);

        regService.register(form);
        var firstToken = extractTokenFromMime(lastMimeMessage.get());

        // 2. Login is blocked before email verification
        assertThat(userDetailsService.loadUserByUsername("anna.nowak@gmail.com").isEnabled()).isFalse();

        // 3. Resend verification invalidates first token and issues a second token
        tokenService.resendVerification("anna.nowak@gmail.com");
        var secondToken = extractTokenFromMime(lastMimeMessage.get());
        assertThat(secondToken).isNotEqualTo(firstToken);
        assertThatThrownBy(() -> tokenService.verifyEmail(firstToken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nieprawidłowy");

        // 4. Advance clock past 24h -> second token expires
        currentTime.set(currentTime.get().plus(Duration.ofHours(24).plusSeconds(1)));
        assertThatThrownBy(() -> tokenService.verifyEmail(secondToken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wygasł albo został już użyty");
        assertThat(userDetailsService.loadUserByUsername("anna.nowak@gmail.com").isEnabled()).isFalse();

        // 5. Resend again, click valid link -> account activated and login enabled
        tokenService.resendVerification("anna.nowak@gmail.com");
        var thirdToken = extractTokenFromMime(lastMimeMessage.get());
        assertThat(tokenService.verifyEmail(thirdToken)).isTrue();
        assertThat(userDetailsService.loadUserByUsername("anna.nowak@gmail.com").isEnabled()).isTrue();

        // 6. Reuse of the same activation link is rejected
        assertThatThrownBy(() -> tokenService.verifyEmail(thirdToken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wygasł albo został już użyty");

        // 7. No tokens were printed to console/IDE logs
        assertThat(output.getAll()).doesNotContain(firstToken, secondToken, thirdToken, "[EMAIL-DEV]");
    }

    private static String extractTokenFromMime(MimeMessage message) throws Exception {
        var out = new ByteArrayOutputStream();
        message.writeTo(out);
        var decoded = out.toString(StandardCharsets.UTF_8)
                .replace("=\r\n", "")
                .replace("=\n", "")
                .replace("=3D", "=");
        var matcher = Pattern.compile("/verify-email\\?token=([A-Za-z0-9_%\\-]+)").matcher(decoded);
        assertThat(matcher.find()).isTrue();
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }
}
