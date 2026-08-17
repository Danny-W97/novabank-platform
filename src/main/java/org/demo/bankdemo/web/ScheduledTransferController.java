package org.demo.bankdemo.web;

import jakarta.validation.Valid;
import org.demo.bankdemo.dashboard.DashboardService;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.ZoneOffset;
import java.util.UUID;

@Controller
@RequestMapping("/scheduled-transfers")
public class ScheduledTransferController {

    private final DashboardService dashboardService;
    private final ScheduledTransferService scheduledTransferService;
    private final TransferService transferService;

    public ScheduledTransferController(
            DashboardService dashboardService,
            ScheduledTransferService scheduledTransferService,
            TransferService transferService
    ) {
        this.dashboardService = dashboardService;
        this.scheduledTransferService = scheduledTransferService;
        this.transferService = transferService;
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
            model.addAttribute("scheduledTransferForm", new ScheduledTransferForm());
        }
        addCommonModel(authentication, model);
        return "transfer/scheduled-form";
    }

    @PostMapping
    String create(
            Authentication authentication,
            @Valid @ModelAttribute ScheduledTransferForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (!bindingResult.hasErrors()) {
            try {
                var command = new TransferCommand(
                        UUID.randomUUID().toString(),
                        form.getRecipientName().strip(),
                        form.accountValue(),
                        form.amountValue(),
                        form.getTitle().strip()
                );
                transferService.preview(authentication.getName(), command);
                scheduledTransferService.create(
                        authentication.getName(),
                        command.destinationAccountNumber(),
                        command.recipientName(),
                        command.amount(),
                        command.title(),
                        form.getFrequency(),
                        form.getFirstExecution().toInstant(ZoneOffset.UTC)
                );
                redirectAttributes.addFlashAttribute("scheduledSuccess", "Przelew został zaplanowany.");
                return "redirect:/scheduled-transfers";
            } catch (TransferException | IllegalArgumentException exception) {
                bindingResult.reject("scheduled.invalid", exception.getMessage());
            }
        }
        addCommonModel(authentication, model);
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
}
