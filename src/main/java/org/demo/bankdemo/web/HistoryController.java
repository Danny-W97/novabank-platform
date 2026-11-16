package org.demo.bankdemo.web;

import jakarta.validation.Valid;
import org.demo.bankdemo.dashboard.DashboardService;
import org.demo.bankdemo.history.HistoryExportService;
import org.demo.bankdemo.history.HistoryFilter;
import org.demo.bankdemo.history.HistoryService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/history")
public class HistoryController {

    private final DashboardService dashboardService;
    private final HistoryService historyService;
    private final HistoryExportService historyExportService;

    public HistoryController(
            DashboardService dashboardService,
            HistoryService historyService,
            HistoryExportService historyExportService
    ) {
        this.dashboardService = dashboardService;
        this.historyService = historyService;
        this.historyExportService = historyExportService;
    }

    @GetMapping
    String list(
            Authentication authentication,
            @Valid @ModelAttribute("filter") HistoryFilter filter,
            BindingResult bindingResult,
            Model model
    ) {
        var safeFilter = bindingResult.hasErrors() ? new HistoryFilter() : filter;
        var history = historyService.historyFor(authentication.getName(), safeFilter);
        model.addAttribute("history", history);
        model.addAttribute("currencies", org.demo.bankdemo.currency.CurrencyCode.values());
        addCommonModel(authentication, model);
        return "history/list";
    }

    @GetMapping("/{transactionId}")
    String detail(
            @PathVariable Long transactionId,
            Authentication authentication,
            Model model
    ) {
        model.addAttribute(
                "transaction",
                historyService.detailFor(authentication.getName(), transactionId)
        );
        model.addAttribute(
                "canRetry",
                historyService.canRetry(authentication.getName(), transactionId)
        );
        addCommonModel(authentication, model);
        return "history/detail";
    }

    @PostMapping("/{transactionId}/retry")
    String retry(
            @PathVariable Long transactionId,
            Authentication authentication,
            RedirectAttributes redirectAttributes
    ) {
        try {
            redirectAttributes.addFlashAttribute(
                    "transferForm",
                    historyService.retryFormFor(authentication.getName(), transactionId)
            );
            return "redirect:/transfer";
        } catch (org.demo.bankdemo.history.HistoryNotFoundException exception) {
            redirectAttributes.addFlashAttribute("transferError", "Nie mozna ponowic tej operacji.");
            return "redirect:/history";
        }
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    ResponseEntity<byte[]> exportCsv(
            Authentication authentication,
            @ModelAttribute HistoryFilter filter
    ) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("historia-operacji.csv", java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString()
                )
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(historyExportService.csv(authentication.getName(), filter));
    }

    @GetMapping(value = "/export.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> exportPdf(
            Authentication authentication,
            @ModelAttribute HistoryFilter filter
    ) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("historia-operacji.pdf")
                                .build()
                                .toString()
                )
                .contentType(MediaType.APPLICATION_PDF)
                .body(historyExportService.pdf(authentication.getName(), filter));
    }

    @GetMapping(value = "/{transactionId}/confirmation.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    ResponseEntity<byte[]> confirmationPdf(
            @PathVariable Long transactionId,
            Authentication authentication
    ) {
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("potwierdzenie-" + transactionId + ".pdf")
                                .build()
                                .toString()
                )
                .contentType(MediaType.APPLICATION_PDF)
                .body(historyExportService.confirmationPdf(authentication.getName(), transactionId));
    }

    private void addCommonModel(Authentication authentication, Model model) {
        FrontendController.addCommonModel(
                model,
                dashboardService.dashboardFor(authentication.getName())
        );
    }
}
