package org.demo.bankdemo.profile;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.util.Utils;
import dev.samstevens.totp.recovery.RecoveryCodeGenerator;
import org.demo.bankdemo.security.TotpSecretCipher;
import org.demo.bankdemo.user.User;
import org.demo.bankdemo.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Service
public class TwoFactorService {

    static final String SETUP_SECRET_ATTRIBUTE = "BANK_TOTP_SETUP_SECRET";
    private static final String ISSUER = "NovaBank";
    private static final int CODE_LENGTH = 6;
    private static final int PERIOD_SECONDS = 30;
    private static final int RECOVERY_CODE_COUNT = 10;

    private final UserRepository userRepository;
    private final TotpRecoveryCodeRepository recoveryCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final TotpSecretCipher secretCipher;
    private final Clock clock;
    private final org.demo.bankdemo.security.TotpReplayGuard replayGuard;
    private final SecretGenerator secretGenerator = new DefaultSecretGenerator();
    private final RecoveryCodeGenerator recoveryCodeGenerator = new RecoveryCodeGenerator();

    public TwoFactorService(
            UserRepository userRepository,
            TotpRecoveryCodeRepository recoveryCodeRepository,
            PasswordEncoder passwordEncoder,
            TotpSecretCipher secretCipher,
            Clock clock,
            org.demo.bankdemo.security.TotpReplayGuard replayGuard
    ) {
        this.userRepository = userRepository;
        this.recoveryCodeRepository = recoveryCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.secretCipher = secretCipher;
        this.clock = clock;
        this.replayGuard = replayGuard;
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(String email) {
        return userFor(email).isTotpEnabled();
    }

    public SetupData setupFor(String email, HttpSession session) {
        if (isEnabled(email)) {
            throw new TwoFactorException("code", "Uwierzytelnianie 2FA jest już włączone.");
        }
        var encryptedPendingSecret = (String) session.getAttribute(SETUP_SECRET_ATTRIBUTE);
        var secret = encryptedPendingSecret == null
                ? secretGenerator.generate()
                : secretCipher.decrypt(encryptedPendingSecret);
        if (encryptedPendingSecret == null) {
            session.setAttribute(SETUP_SECRET_ATTRIBUTE, secretCipher.encrypt(secret));
        }
        try {
            var data = new QrData.Builder()
                    .label(email)
                    .secret(secret)
                    .issuer(ISSUER)
                    .algorithm(HashingAlgorithm.SHA1)
                    .digits(CODE_LENGTH)
                    .period(PERIOD_SECONDS)
                    .build();
            QrGenerator generator = new ZxingPngQrGenerator();
            var image = generator.generate(data);
            return new SetupData(secret, Utils.getDataUriForImage(image, generator.getImageMimeType()));
        } catch (Exception exception) {
            throw new IllegalStateException("Nie można wygenerować kodu QR dla 2FA.", exception);
        }
    }

    @Transactional
    public List<String> confirmSetup(String email, String code, HttpSession session) {
        var pending = (String) session.getAttribute(SETUP_SECRET_ATTRIBUTE);
        if (pending == null) {
            throw new TwoFactorException("code", "Sesja konfiguracji 2FA wygasła. Wygeneruj nowy kod QR.");
        }
        var secret = secretCipher.decrypt(pending);
        var step = matchingStep(secret, code);
        if (step == null) {
            throw new TwoFactorException("code", "Kod 2FA jest nieprawidłowy lub wygasł.");
        }

        var user = userRepository.findByEmailForUpdate(email.strip().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalStateException("Nie znaleziono użytkownika."));
        if (user.isTotpEnabled()) {
            throw new TwoFactorException("code", "Uwierzytelnianie 2FA jest już włączone.");
        }
        user.enableTotp(secretCipher.encrypt(secret), Instant.now(clock));
        if (!replayGuard.consume(user.getId(), secret, step))
            throw new TwoFactorException("code", "Kod został już użyty. Poczekaj na kolejny kod.");
        recoveryCodeRepository.deleteByUser_Id(user.getId());
        var recoveryCodes = Arrays.asList(recoveryCodeGenerator.generateCodes(RECOVERY_CODE_COUNT));
        recoveryCodeRepository.saveAll(recoveryCodes.stream()
                .map(value -> new TotpRecoveryCode(user, passwordEncoder.encode(value), Instant.now(clock)))
                .toList());
        session.removeAttribute(SETUP_SECRET_ATTRIBUTE);
        return recoveryCodes;
    }

    @Transactional
    public boolean verifyTotpCode(String email, String code) {
        var user = userFor(email);
        return user.isTotpEnabled()
                && consumeTotp(user, code);
    }

    @Transactional
    public boolean verifyLoginCode(String email, String code) {
        var user = userFor(email);
        if (!user.isTotpEnabled()) {
            return true;
        }
        if (consumeTotp(user, code)) {
            return true;
        }
        var normalized = normalizeRecoveryCode(code);
        if (normalized.isBlank()) {
            return false;
        }
        var now = Instant.now(clock);
        for (var recoveryCode : recoveryCodeRepository.findUnusedForUpdate(user.getId())) {
            if (passwordEncoder.matches(normalized, recoveryCode.getCodeHash())) {
                recoveryCode.markUsed(now);
                return true;
            }
        }
        return false;
    }

    @Transactional
    public void disable(String email, String currentPassword, String code) {
        var user = userFor(email);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new TwoFactorException("currentPassword", "Hasło jest nieprawidłowe.");
        }
        if (!verifyTotpCode(email, code)) {
            throw new TwoFactorException("code", "Kod 2FA jest nieprawidłowy lub wygasł.");
        }
        user.disableTotp();
        recoveryCodeRepository.deleteByUser_Id(user.getId());
    }

    private User userFor(String email) {
        return userRepository.findByEmail(email.strip().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalStateException("Nie znaleziono użytkownika."));
    }

    private boolean consumeTotp(User user, String code) {
        var secret = secretCipher.decrypt(user.getTotpSecretEncrypted());
        var step = matchingStep(secret, code);
        return step != null && replayGuard.consume(user.getId(), secret, step);
    }

    private Long matchingStep(String secret, String code) {
        if (code == null || !code.matches("\\d{" + CODE_LENGTH + "}")) {
            return null;
        }
        CodeGenerator generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, CODE_LENGTH);
        long current = Instant.now(clock).getEpochSecond() / PERIOD_SECONDS;
        try {
            for (long step = current - 1; step <= current + 1; step++) {
                if (generator.generate(secret, step).equals(code)) return step;
            }
            return null;
        } catch (Exception exception) { throw new IllegalStateException("Nie można zweryfikować TOTP.", exception); }
    }

    private static String normalizeRecoveryCode(String code) {
        return code == null ? "" : code.replace(" ", "").strip().toLowerCase(Locale.ROOT);
    }

    public record SetupData(String manualKey, String qrDataUri) {
    }
}
