package org.demo.bankdemo.web;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.demo.bankdemo.audit.OperationAuditService;
import org.demo.bankdemo.profile.TwoFactorService;
import org.demo.bankdemo.dashboard.DashboardService;
import org.demo.bankdemo.profile.TransactionPinService;
import org.demo.bankdemo.recipient.RecipientService;
import org.demo.bankdemo.transfer.TransferDraftStore;
import org.demo.bankdemo.transfer.TransferCommand;
import org.demo.bankdemo.transfer.TransferException;
import org.demo.bankdemo.transfer.TransferForm;
import org.demo.bankdemo.transfer.TransferService;
import org.demo.bankdemo.transfer.TransferSecurityService;
import org.demo.bankdemo.transfer.TransferTotpChallengeService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/transfer")
public class TransferController {

    private final DashboardService dashboardService;
    private final TransferService transferService;
    private final TransferDraftStore draftStore;
    private final RecipientService recipientService;
    private final TransactionPinService transactionPinService;
    private final OperationAuditService auditService;
    private final TwoFactorService twoFactorService;
    private final TransferSecurityService transferSecurityService;
    private final TransferTotpChallengeService totpChallengeService;

    @org.springframework.beans.factory.annotation.Autowired
    public TransferController(
            DashboardService dashboardService,
            TransferService transferService,
            TransferDraftStore draftStore,
            RecipientService recipientService,
            TransactionPinService transactionPinService,
            OperationAuditService auditService,
            TwoFactorService twoFactorService,
            TransferSecurityService transferSecurityService,
            TransferTotpChallengeService totpChallengeService
    ) {
        this.dashboardService = dashboardService;
        this.transferService = transferService;
        this.draftStore = draftStore;
        this.recipientService = recipientService;
        this.transactionPinService = transactionPinService;
        this.auditService = auditService;
        this.twoFactorService = twoFactorService;
        this.transferSecurityService = transferSecurityService;
        this.totpChallengeService = totpChallengeService;
    }

    public TransferController(
            DashboardService dashboardService,
            TransferService transferService,
            TransferDraftStore draftStore,
            RecipientService recipientService,
            TransactionPinService transactionPinService,
            OperationAuditService auditService
    ) {
        this(dashboardService, transferService, draftStore, recipientService,
                transactionPinService, auditService, null, null, null);
    }

    @GetMapping
    String form(
            Authentication authentication,
            @RequestParam(required = false) Long recipientId,
            Model model
    ) {
        if (!model.containsAttribute("transferForm")) {
            var transferForm = new TransferForm();
            transferForm.setIdempotencyKey(UUID.randomUUID().toString());
            if (recipientId != null) {
                var recipient = recipientService.recipientFor(authentication.getName(), recipientId);
                transferForm.setRecipientName(recipient.recipientName());
                transferForm.setDestinationAccountNumber(recipient.accountNumber().replace(" ", ""));
            }
            model.addAttribute("transferForm", transferForm);
        }
        addCommonModel(authentication, model);
        return "transfer/form";
    }

    @PostMapping("/confirm")
    String confirm(
            Authentication authentication,
            @Valid @ModelAttribute TransferForm transferForm,
            BindingResult bindingResult,
            HttpSession session,
            Model model
    ) {
        if (!bindingResult.hasErrors()) {
            try {
                var command = transferForm.toCommand();
                var preview = transferService.preview(authentication.getName(), command);
                draftStore.save(session, command);
                model.addAttribute("preview", preview);
                model.addAttribute("pinRequired", transactionPinService.isConfigured(authentication.getName()));
                prepareTotp(authentication.getName(), command, session, model);
            } catch (TransferException exception) {
                auditService.recordFailure(authentication.getName(), "TRANSFER_VALIDATION", exception.getMessage());
                reject(bindingResult, exception);
            } catch (NumberFormatException exception) {
                var message = "Podaj poprawną kwotę przelewu.";
                auditService.recordFailure(authentication.getName(), "TRANSFER_VALIDATION", message);
                bindingResult.rejectValue("amount", "amount.invalid", message);
            }
        }

        addCommonModel(authentication, model);
        return bindingResult.hasErrors() ? "transfer/form" : "transfer/confirm";
    }

    @PostMapping("/edit")
    String edit(
            @RequestParam String idempotencyKey,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        draftStore.find(session, idempotencyKey)
                .map(TransferForm::from)
                .ifPresent(form -> redirectAttributes.addFlashAttribute("transferForm", form));
        return "redirect:/transfer";
    }

    String execute(
            Authentication authentication,
            @RequestParam String idempotencyKey,
            @RequestParam(required = false) String pin,
            HttpSession session,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        return execute(authentication, idempotencyKey, pin, null, session, model, redirectAttributes);
    }

    @PostMapping("/execute")
    String execute(
            Authentication authentication,
            @RequestParam String idempotencyKey,
            @RequestParam(required = false) String pin,
            @RequestParam(required = false) String totpCode,
            HttpSession session,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        var command = draftStore.find(session, idempotencyKey);
        if (command.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "transferError",
                    "Potwierdzenie przelewu wygasło. Wprowadź dane ponownie."
            );
            return "redirect:/transfer";
        }

        try {
            transactionPinService.verifyForTransfer(authentication.getName(), pin);
            if (transferSecurityService != null) {
                var requirement = transferSecurityService.evaluate(authentication.getName(), command.get(), session);
                if (requirement.required()) {
                    if (!twoFactorService.isEnabled(authentication.getName())) {
                        return renderTotpRequired(authentication, command.get(), session, model,
                                "Dla tego przelewu wymagane jest 2FA. Wlacz Google Authenticator w profilu.");
                    }
                    var verification = totpChallengeService.verify(
                            session, authentication.getName(), command.get(), totpCode
                    );
                    if (verification != TransferTotpChallengeService.VerificationResult.VERIFIED) {
                        auditService.recordFailure(authentication.getName(), "TRANSFER_TOTP", verification.name());
                        return renderTotpRequired(authentication, command.get(), session, model,
                                totpMessage(verification));
                    }
                    session.removeAttribute(org.demo.bankdemo.audit.LoginHistoryService.NEW_DEVICE_ATTRIBUTE);
                    auditService.recordSuccess(authentication.getName(), "TRANSFER_TOTP",
                            "Poprawnie potwierdzono przelew kodem TOTP.");
                }
            }
            var result = transferService.execute(authentication.getName(), command.get());
            return "redirect:/transfer/success/" + result.referenceNumber();
        } catch (TransferException exception) {
            auditService.recordFailure(
                    authentication.getName(),
                    "TRANSFER",
                    exception.getMessage()
            );
            if ("pin".equals(exception.getField())) {
                model.addAttribute("transferForm", TransferForm.from(command.get()));
                model.addAttribute(
                        "preview",
                        transferService.preview(authentication.getName(), command.get())
                );
                model.addAttribute(
                        "pinRequired",
                        transactionPinService.isConfigured(authentication.getName())
                );
                prepareTotp(authentication.getName(), command.get(), session, model);
                model.addAttribute("pinError", exception.getMessage());
                addCommonModel(authentication, model);
                return "transfer/confirm";
            }
            redirectAttributes.addFlashAttribute("transferForm", TransferForm.from(command.get()));
            redirectAttributes.addFlashAttribute("transferError", exception.getMessage());
            return "redirect:/transfer";
        }
    }

    private void prepareTotp(String email, TransferCommand command,
                             HttpSession session, Model model) {
        if (transferSecurityService == null) {
            return;
        }
        var requirement = transferSecurityService.evaluate(email, command, session);
        model.addAttribute("totpRequired", requirement.required());
        model.addAttribute("totpReason", requirement.reason());
        model.addAttribute("totpEnabled", twoFactorService.isEnabled(email));
        if (requirement.required() && twoFactorService.isEnabled(email)) {
            totpChallengeService.start(session, email, command, requirement.reason());
        }
    }

    private String renderTotpRequired(Authentication authentication, TransferCommand command,
                                     HttpSession session, Model model, String error) {
        model.addAttribute("transferForm", TransferForm.from(command));
        model.addAttribute("preview", transferService.preview(authentication.getName(), command));
        model.addAttribute("pinRequired", transactionPinService.isConfigured(authentication.getName()));
        prepareTotp(authentication.getName(), command, session, model);
        model.addAttribute("totpError", error);
        addCommonModel(authentication, model);
        return "transfer/confirm";
    }

    private static String totpMessage(TransferTotpChallengeService.VerificationResult result) {
        return switch (result) {
            case INVALID_CODE -> "Kod TOTP jest nieprawidlowy lub wygasl.";
            case EXPIRED -> "Wyzwanie TOTP wygaslo. Wygenerowano nowe wyzwanie.";
            case MISMATCH -> "Dane przelewu zmienily sie. Potwierdz przelew ponownie.";
            case TOO_MANY_ATTEMPTS -> "Przekroczono liczbe prob kodu TOTP. Potwierdz przelew ponownie.";
            case VERIFIED -> "";
        };
    }

    @GetMapping("/success/{referenceNumber}")
    String success(
            @PathVariable String referenceNumber,
            Authentication authentication,
            Model model
    ) {
        model.addAttribute(
                "transfer",
                transferService.resultFor(referenceNumber, authentication.getName())
        );
        addCommonModel(authentication, model);
        return "transfer/success";
    }

    private void addCommonModel(Authentication authentication, Model model) {
        FrontendController.addCommonModel(
                model,
                dashboardService.dashboardFor(authentication.getName())
        );
    }

    private static void reject(BindingResult bindingResult, TransferException exception) {
        if (exception.getField() == null) {
            bindingResult.reject("transfer.invalid", exception.getMessage());
        } else {
            bindingResult.rejectValue(
                    exception.getField(),
                    "transfer.invalid",
                    exception.getMessage()
            );
        }
    }
}
