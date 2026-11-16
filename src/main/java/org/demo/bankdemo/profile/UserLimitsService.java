package org.demo.bankdemo.profile;

import org.demo.bankdemo.audit.OperationAuditService;
import org.demo.bankdemo.card.CardException;
import org.demo.bankdemo.card.CardService;
import org.demo.bankdemo.config.TransferLimitsProperties;
import org.demo.bankdemo.user.User;
import org.demo.bankdemo.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;

@Service
public class UserLimitsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TwoFactorService twoFactorService;
    private final TransferLimitsProperties bankLimits;
    private final OperationAuditService auditService;
    private final CardService cardService;

    public UserLimitsService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TwoFactorService twoFactorService,
            TransferLimitsProperties bankLimits,
            OperationAuditService auditService,
            CardService cardService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.twoFactorService = twoFactorService;
        this.bankLimits = bankLimits;
        this.auditService = auditService;
        this.cardService = cardService;
    }

    @Transactional(readOnly = true)
    public UserLimitsView limitsFor(String email) {
        var user = userFor(email);
        var cardLimit = cardService.dailyLimitFor(email);
        return new UserLimitsView(
                effective(user.getTransferSingleLimit(), bankLimits.getSingleLimit()),
                effective(user.getTransferDailyLimit(), bankLimits.getDailyLimit()),
                effective(user.getPaymentSingleLimit(), bankLimits.getSingleLimit()),
                effective(user.getPaymentDailyLimit(), bankLimits.getDailyLimit()),
                effective(cardLimit, CardService.DEFAULT_DAILY_LIMIT),
                cardLimit != null,
                effective(user.getTotpRequiredAbove(), bankLimits.getTotpRequiredAbove()),
                bankLimits.getTotpRequiredAbove(),
                bankLimits.getSingleLimit(),
                bankLimits.getDailyLimit()
        );
    }

    @Transactional
    public void update(String email, UserLimitsForm form) {
        var normalizedEmail = normalize(email);
        var user = userFor(normalizedEmail);
        try {
            if (!passwordEncoder.matches(form.getCurrentPassword(), user.getPasswordHash())) {
                throw new ProfileException("currentPassword", "Haslo jest nieprawidlowe.");
            }
            if (!user.isTotpEnabled()) {
                throw new ProfileException("totpCode", "Najpierw wlacz 2FA w Google Authenticator.");
            }
            if (!twoFactorService.verifyTotpCode(normalizedEmail, form.getTotpCode())) {
                throw new ProfileException("totpCode", "Kod TOTP jest nieprawidlowy lub wygasl.");
            }
            validate(form);
            user.updateLimits(
                    form.getTransferSingleLimit(),
                    form.getTransferDailyLimit(),
                    form.getPaymentSingleLimit(),
                    form.getPaymentDailyLimit()
            );
            user.updateTotpRequiredAbove(form.getTotpRequiredAbove());
            try {
                cardService.updateDailyLimit(normalizedEmail, form.getCardDailyLimit());
            } catch (CardException exception) {
                throw new ProfileException("cardDailyLimit", exception.getMessage());
            }
            auditService.recordSuccess(
                    normalizedEmail,
                    "USER_LIMITS_CHANGED",
                    "Zmieniono limity przelewow, platnosci, karty i prog TOTP po potwierdzeniu haslem i TOTP."
            );
        } catch (ProfileException exception) {
            auditService.recordFailure(normalizedEmail, "USER_LIMITS_CHANGED", exception.getMessage());
            throw exception;
        }
    }

    public UserLimitsForm formFor(String email) {
        var limits = limitsFor(email);
        var form = new UserLimitsForm();
        form.setTransferSingleLimit(limits.transferSingleLimit());
        form.setTransferDailyLimit(limits.transferDailyLimit());
        form.setPaymentSingleLimit(limits.paymentSingleLimit());
        form.setPaymentDailyLimit(limits.paymentDailyLimit());
        form.setCardDailyLimit(limits.cardDailyLimit());
        form.setTotpRequiredAbove(limits.totpRequiredAbove());
        return form;
    }

    private void validate(UserLimitsForm form) {
        validateAtMost(form.getTransferSingleLimit(), bankLimits.getSingleLimit(), "transferSingleLimit",
                "Limit pojedynczego przelewu nie moze przekraczac limitu bankowego.");
        validateAtMost(form.getTransferDailyLimit(), bankLimits.getDailyLimit(), "transferDailyLimit",
                "Dzienny limit przelewow nie moze przekraczac limitu bankowego.");
        validateAtMost(form.getPaymentSingleLimit(), bankLimits.getSingleLimit(), "paymentSingleLimit",
                "Limit pojedynczej platnosci nie moze przekraczac limitu bankowego.");
        validateAtMost(form.getPaymentDailyLimit(), bankLimits.getDailyLimit(), "paymentDailyLimit",
                "Dzienny limit platnosci nie moze przekraczac limitu bankowego.");
        validateAtMost(form.getCardDailyLimit(), bankLimits.getDailyLimit(), "cardDailyLimit",
                "Dzienny limit karty nie moze przekraczac limitu bankowego.");
        validateAtMost(form.getTotpRequiredAbove(), bankLimits.getTotpRequiredAbove(), "totpRequiredAbove",
                "Prog TOTP nie moze przekraczac maksymalnego progu bankowego.");
        if (form.getTransferDailyLimit().compareTo(form.getTransferSingleLimit()) < 0) {
            throw new ProfileException("transferDailyLimit", "Dzienny limit przelewow nie moze byc nizszy od limitu pojedynczego przelewu.");
        }
        if (form.getPaymentDailyLimit().compareTo(form.getPaymentSingleLimit()) < 0) {
            throw new ProfileException("paymentDailyLimit", "Dzienny limit platnosci nie moze byc nizszy od limitu pojedynczej platnosci.");
        }
    }

    private static void validateAtMost(BigDecimal value, BigDecimal maximum, String field, String message) {
        if (value.compareTo(maximum) > 0) {
            throw new ProfileException(field, message);
        }
    }

    private User userFor(String email) {
        return userRepository.findByEmail(normalize(email))
                .orElseThrow(() -> new IllegalStateException("Nie znaleziono uzytkownika."));
    }

    private static BigDecimal effective(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
