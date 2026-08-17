package org.demo.bankdemo.transfer;

import jakarta.servlet.http.HttpSession;
import org.demo.bankdemo.audit.LoginHistoryService;
import org.demo.bankdemo.config.TransferLimitsProperties;
import org.demo.bankdemo.recipient.RecipientService;
import org.demo.bankdemo.user.UserRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Locale;

@Service
public class TransferSecurityService {

    private final TransferLimitsProperties limits;
    private final RecipientService recipientService;
    private final UserRepository userRepository;

    public TransferSecurityService(
            TransferLimitsProperties limits,
            RecipientService recipientService,
            UserRepository userRepository
    ) {
        this.limits = limits;
        this.recipientService = recipientService;
        this.userRepository = userRepository;
    }

    public Requirement evaluate(String email, TransferCommand command, HttpSession session) {
        var reasons = new ArrayList<String>();
        var threshold = userRepository.findByEmail(email.strip().toLowerCase(Locale.ROOT))
                .map(user -> user.getTotpRequiredAbove() == null
                        ? limits.getTotpRequiredAbove()
                        : user.getTotpRequiredAbove().min(limits.getTotpRequiredAbove()))
                .orElse(limits.getTotpRequiredAbove());
        if (command.amount().compareTo(threshold) > 0) {
            reasons.add("kwota przekracza " + threshold.toPlainString() + " PLN");
        }
        if (!recipientService.isSavedRecipient(email, command.destinationAccountNumber())) {
            reasons.add("odbiorca nie jest zapisany");
        }
        if (Boolean.TRUE.equals(session.getAttribute(LoginHistoryService.NEW_DEVICE_ATTRIBUTE))) {
            reasons.add("wykryto nowe urzadzenie");
        }
        return new Requirement(!reasons.isEmpty(), String.join(", ", reasons));
    }

    public record Requirement(boolean required, String reason) { }
}
