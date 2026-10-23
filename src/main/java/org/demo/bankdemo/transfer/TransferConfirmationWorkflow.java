package org.demo.bankdemo.transfer;

import jakarta.servlet.http.HttpSession;
import org.demo.bankdemo.audit.LoginHistoryService;
import org.demo.bankdemo.audit.OperationAuditService;
import org.demo.bankdemo.profile.TransactionPinService;
import org.demo.bankdemo.profile.TwoFactorService;
import org.springframework.stereotype.Service;

/** Security orchestration stays outside the financial transaction and is required in every caller. */
@Service
public class TransferConfirmationWorkflow {
    private final TransferService transfers;
    private final TransactionPinService pins;
    private final TwoFactorService twoFactor;
    private final TransferSecurityService security;
    private final TransferTotpChallengeService challenges;
    private final OperationAuditService audit;

    public TransferConfirmationWorkflow(TransferService transfers, TransactionPinService pins,
            TwoFactorService twoFactor, TransferSecurityService security,
            TransferTotpChallengeService challenges, OperationAuditService audit) {
        this.transfers = java.util.Objects.requireNonNull(transfers);
        this.pins = java.util.Objects.requireNonNull(pins);
        this.twoFactor = java.util.Objects.requireNonNull(twoFactor);
        this.security = java.util.Objects.requireNonNull(security);
        this.challenges = java.util.Objects.requireNonNull(challenges);
        this.audit = java.util.Objects.requireNonNull(audit);
    }

    public Preparation prepare(String email, TransferCommand command, HttpSession session) {
        var requirement = security.evaluate(email, command, session);
        var enabled = twoFactor.isEnabled(email);
        if (requirement.required() && enabled) challenges.start(session, email, command, requirement.reason());
        return new Preparation(requirement.required(), requirement.reason(), enabled);
    }

    public Outcome execute(String email, TransferCommand command, String pin, String code, HttpSession session) {
        // The query validates owner and the complete immutable command; it cannot authorize a new transfer.
        var completed = transfers.findCompleted(email, command);
        if (completed.isPresent()) return new Outcome(completed.get(), null);
        pins.verifyForTransfer(email, pin);
        var requirement = security.evaluate(email, command, session);
        if (requirement.required()) {
            if (!twoFactor.isEnabled(email)) return new Outcome(null,
                    "Dla tego przelewu wymagane jest 2FA. Wlacz Google Authenticator w profilu.");
            var verification = challenges.verify(session, email, command, code);
            if (verification != TransferTotpChallengeService.VerificationResult.VERIFIED) {
                audit.recordFailure(email, "TRANSFER_TOTP", verification.name());
                return new Outcome(null, message(verification));
            }
            session.removeAttribute(LoginHistoryService.NEW_DEVICE_ATTRIBUTE);
            audit.recordSuccess(email, "TRANSFER_TOTP", "Poprawnie potwierdzono przelew kodem TOTP.");
        }
        return new Outcome(transfers.execute(email, command), null);
    }

    private static String message(TransferTotpChallengeService.VerificationResult result) {
        return switch (result) {
            case TOO_MANY_ATTEMPTS -> "Przekroczono limit prób. Potwierdź przelew nowym kodem.";
            case EXPIRED -> "Wyzwanie wygasło lub zostało wykorzystane. Sprawdź wynik i użyj nowego kodu dla niewykonanego przelewu.";
            case MISMATCH -> "Dane przelewu zmieniły się. Potwierdź je ponownie.";
            default -> "Kod jest niepoprawny albo został już wykorzystany. Poczekaj na nowy kod.";
        };
    }

    public record Preparation(boolean required, String reason, boolean enabled) { }
    public record Outcome(TransferResult result, String confirmationMessage) { }
}
