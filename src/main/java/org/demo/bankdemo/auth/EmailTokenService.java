package org.demo.bankdemo.auth;

import org.demo.bankdemo.profile.UserSessionService;
import org.demo.bankdemo.notification.NotificationService;
import org.demo.bankdemo.user.User;
import org.demo.bankdemo.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

@Service
public class EmailTokenService {

    private static final Duration VERIFICATION_LIFETIME = Duration.ofHours(24);
    private static final Duration RESET_LIFETIME = Duration.ofMinutes(30);

    private final EmailVerificationTokenRepository verificationRepository;
    private final PasswordResetTokenRepository resetRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailDeliveryService emailDeliveryService;
    private final UserSessionService userSessionService;
    private final Clock clock;
    private final NotificationService notificationService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public EmailTokenService(
            EmailVerificationTokenRepository verificationRepository,
            PasswordResetTokenRepository resetRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            EmailDeliveryService emailDeliveryService,
            UserSessionService userSessionService,
            Clock clock,
            NotificationService notificationService
    ) {
        this.verificationRepository = verificationRepository;
        this.resetRepository = resetRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailDeliveryService = emailDeliveryService;
        this.userSessionService = userSessionService;
        this.clock = clock;
        this.notificationService = notificationService;
    }

    public EmailTokenService(
            EmailVerificationTokenRepository verificationRepository,
            PasswordResetTokenRepository resetRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            EmailDeliveryService emailDeliveryService,
            UserSessionService userSessionService,
            Clock clock
    ) {
        this(verificationRepository, resetRepository, userRepository, passwordEncoder,
                emailDeliveryService, userSessionService, clock, null);
    }

    @Transactional
    public void issueVerification(User user) {
        if (user.isEmailVerified()) {
            return;
        }
        verificationRepository.deleteAllByUserId(user.getId());
        var rawToken = randomToken();
        verificationRepository.save(new EmailVerificationToken(
                user,
                hash(rawToken),
                Instant.now(clock).plus(VERIFICATION_LIFETIME)
        ));
        deliverSafely(() -> emailDeliveryService.sendVerification(user.getEmail(), rawToken));
    }

    @Transactional
    public boolean verifyEmail(String rawToken) {
        var tokenHash = hash(rawToken);
        var token = verificationRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("Link potwierdzający jest nieprawidłowy."));
        var now = Instant.now(clock);
        if (verificationRepository.consumeUnused(tokenHash, now) != 1) {
            throw new IllegalArgumentException("Link potwierdzający wygasł albo został już użyty.");
        }
        token.getUser().verifyEmail(now);
        return true;
    }

    @Transactional
    public void resendVerification(String email) {
        userRepository.findByEmail(RegistrationService.normalizeEmail(email))
                .filter(user -> !user.isEmailVerified())
                .ifPresent(this::issueVerification);
    }

    @Transactional
    public void requestPasswordReset(String email) {
        userRepository.findByEmail(RegistrationService.normalizeEmail(email))
                .ifPresent(user -> {
                    resetRepository.deleteAllByUserId(user.getId());
                    var rawToken = randomToken();
                    resetRepository.save(new PasswordResetToken(
                            user,
                            hash(rawToken),
                            Instant.now(clock).plus(RESET_LIFETIME)
                    ));
                    deliverSafely(() -> emailDeliveryService.sendPasswordReset(user.getEmail(), rawToken));
                });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        var tokenHash = hash(rawToken);
        var token = resetRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("Link resetowania hasła jest nieprawidłowy."));
        var now = Instant.now(clock);
        if (resetRepository.consumeUnused(tokenHash, now) != 1) {
            throw new IllegalArgumentException("Link resetowania hasła wygasł albo został już użyty.");
        }
        var user = token.getUser();
        user.changePassword(passwordEncoder.encode(newPassword), now);
        userSessionService.invalidateAllSessions(user.getEmail());
        if (notificationService != null) {
            notificationService.securityEvent(
                    user.getEmail(),
                    "Haslo do konta zostalo zresetowane. Jezeli to nie Ty wykonales te operacje, skontaktuj sie z bankiem."
            );
        }
    }

    private void deliverSafely(Runnable delivery) {
        try {
            delivery.run();
        } catch (RuntimeException exception) {
            System.err.println("Nie udało się wysłać wiadomości e-mail: " + exception.getMessage());
        }
    }

    private String randomToken() {
        var bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Token jest wymagany.");
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.strip().getBytes(StandardCharsets.UTF_8));
            var builder = new StringBuilder(64);
            for (var value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Nie można wygenerować skrótu tokenu.", exception);
        }
    }
}
