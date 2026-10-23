package org.demo.bankdemo.web;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpSession;
import org.demo.bankdemo.dashboard.DashboardService;
import org.demo.bankdemo.profile.TransactionPinService;
import org.demo.bankdemo.profile.TwoFactorService;
import org.demo.bankdemo.transfer.TransferSecurityService;
import org.demo.bankdemo.transfer.ScheduledTransferForm;
import org.demo.bankdemo.transfer.ScheduledTransferService;
import org.demo.bankdemo.transfer.TransferCommand;
import org.demo.bankdemo.transfer.TransferException;
import org.demo.bankdemo.transfer.TransferService;
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

import java.time.ZoneId;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Controller
@RequestMapping("/scheduled-transfers")
public class ScheduledTransferController {

    private final DashboardService dashboardService;
    private final ScheduledTransferService scheduledTransferService;
    private final TransferService transferService;
    private final TransactionPinService transactionPinService;
    private final TransferSecurityService transferSecurityService;
    private final TwoFactorService twoFactorService;

    public ScheduledTransferController(
            DashboardService dashboardService,
            ScheduledTransferService scheduledTransferService,
            TransferService transferService,
            TransactionPinService transactionPinService,
            TransferSecurityService transferSecurityService,
            TwoFactorService twoFactorService
    ) {
        this.dashboardService = dashboardService;
        this.scheduledTransferService = scheduledTransferService;
        this.transferService = transferService;
        this.transactionPinService = transactionPinService;
        this.transferSecurityService = transferSecurityService;
        this.twoFactorService = twoFactorService;
    }

    @GetMapping
    String list(Authentication authentication, Model model) {
        addCommonModel(authentication, model);
        model.addAttribute("scheduledTransfers", scheduledTransferService.forUser(authentication.getName()));
        return "transfer/scheduled-list";
    }

    @GetMapping("/new")
    String form(Authentication authentication, Model model) {
        if (!model.containsAttribute("scheduledTransferForm")) {
            var form = new ScheduledTransferForm();
            form.setIdempotencyKey(UUID.randomUUID().toString());
            model.addAttribute("scheduledTransferForm", form);
        }
        addCommonModel(authentication, model);
        addSecurityModel(authentication, model);
        return "transfer/scheduled-form";
    }

    @PostMapping
    String create(
            Authentication authentication,
            @Valid @ModelAttribute ScheduledTransferForm form,
            BindingResult bindingResult,
            @RequestParam(required = false) String pin,
            @RequestParam(required = false) String totpCode,
            HttpSession session,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!bindingResult.hasErrors()) {
            try {
                var command = new TransferCommand(
                        form.getIdempotencyKey(),
                        form.getRecipientName().strip(),
                        form.accountValue(),
                        form.amountValue(),
                        form.getTitle().strip()
                );
                var firstExecution = firstExecutionInstant(form.getFirstExecution());
                if (scheduledTransferService.isAlreadyCreated(authentication.getName(), command,
                        form.getFrequency(), firstExecution)) {
                    redirectAttributes.addFlashAttribute("scheduledSuccess", "Przelew został już zaplanowany.");
                    return "redirect:/scheduled-transfers";
                }
                transferService.preview(authentication.getName(), command);
                transactionPinService.verifyForTransfer(authentication.getName(), pin);
                var requirement = transferSecurityService.evaluate(authentication.getName(), command, session);
                if (requirement.required()) {
                    if (!twoFactorService.isEnabled(authentication.getName())) {
                        throw new TransferException(null,
                                "Ten przelew wymaga 2FA. Włącz je w profilu przed utworzeniem zlecenia.");
                    }
                    if (!twoFactorService.verifyTotpCode(authentication.getName(), totpCode)) {
                        throw new TransferException(null, "Kod TOTP jest nieprawidłowy lub wygasł.");
                    }
                }
                scheduledTransferService.create(
                        authentication.getName(),
                        command.idempotencyKey(),
                        command.destinationAccountNumber(),
                        command.recipientName(),
                        command.amount(),
                        command.title(),
                        form.getFrequency(),
                        firstExecution
                );
                if (requirement.required()) {
                    session.removeAttribute(org.demo.bankdemo.audit.LoginHistoryService.NEW_DEVICE_ATTRIBUTE);
                }
                redirectAttributes.addFlashAttribute("scheduledSuccess", "Przelew został zaplanowany.");
                return "redirect:/scheduled-transfers";
            } catch (TransferException | IllegalArgumentException exception) {
                bindingResult.reject("scheduled.invalid", exception.getMessage());
            }
        }
        addCommonModel(authentication, model);
        addSecurityModel(authentication, model);
        return "transfer/scheduled-form";
    }

    @PostMapping("/{id}/cancel")
    String cancel(
            Authentication authentication,
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            scheduledTransferService.cancel(authentication.getName(), id);
            redirectAttributes.addFlashAttribute("scheduledSuccess", "Przelew zaplanowany został anulowany.");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("scheduledError", exception.getMessage());
        }
        return "redirect:/scheduled-transfers";
    }

    private void addCommonModel(Authentication authentication, Model model) {
        FrontendController.addCommonModel(model, dashboardService.dashboardFor(authentication.getName()));
    }

    private void addSecurityModel(Authentication authentication, Model model) {
        model.addAttribute("pinRequired", transactionPinService.isConfigured(authentication.getName()));
        model.addAttribute("totpEnabled", twoFactorService.isEnabled(authentication.getName()));
    }

    static Instant firstExecutionInstant(LocalDateTime localTime) {
        var zone = ZoneId.of("Europe/Warsaw");
        var offsets = zone.getRules().getValidOffsets(localTime);
        if (offsets.size() != 1) {
            throw new IllegalArgumentException(
                    "Podana godzina nie istnieje lub jest niejednoznaczna przy zmianie czasu w Polsce.");
        }
        return localTime.atOffset(offsets.getFirst()).toInstant();
    }
}
