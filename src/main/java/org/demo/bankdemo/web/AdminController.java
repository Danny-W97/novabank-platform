package org.demo.bankdemo.web;

import jakarta.servlet.http.HttpServletRequest;
import org.demo.bankdemo.admin.AdminService;
import org.demo.bankdemo.audit.OperationAuditService;
import org.demo.bankdemo.currency.CurrencyCode;
import org.demo.bankdemo.currency.ExchangeException;
import org.demo.bankdemo.currency.ExchangeService;
import org.demo.bankdemo.dashboard.DashboardService;
import org.demo.bankdemo.config.RetentionProperties;
import org.demo.bankdemo.retention.RetentionExecutionService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final AdminService adminService;
    private final DashboardService dashboardService;
    private final ExchangeService exchangeService;
    private final OperationAuditService auditService;
    private final RetentionExecutionService retentionExecutionService;
    private final RetentionProperties retentionProperties;

    public AdminController(
            AdminService adminService,
            DashboardService dashboardService,
            ExchangeService exchangeService,
            OperationAuditService auditService,
            RetentionExecutionService retentionExecutionService,
            RetentionProperties retentionProperties
    ) {
        this.adminService = adminService;
        this.dashboardService = dashboardService;
        this.exchangeService = exchangeService;
        this.auditService = auditService;
        this.retentionExecutionService = retentionExecutionService;
        this.retentionProperties = retentionProperties;
    }

    @GetMapping
    String dashboard(
            Authentication authentication,
            @RequestParam(required = false) String q,
            HttpServletRequest request,
            Model model
    ) {
        model.addAttribute("admin", q == null || q.isBlank() ? adminService.dashboard() : adminService.dashboard(q));
        model.addAttribute("adminQuery", q == null ? "" : q);
        if (q != null && !q.isBlank()) {
            auditService.recordAdminAction(authentication.getName(), "ADMIN_USER_SEARCH", null, null,
                    "Wyszukiwanie uzytkownikow: " + q.strip(), true, request.getRemoteAddr());
        }
        model.addAttribute("exchangeRates", exchangeService.rates());
        model.addAttribute("adminEmail", authentication.getName());
        FrontendController.addCommonModel(model, dashboardService.dashboardFor(authentication.getName()));
        return "admin/dashboard";
    }

    @PostMapping("/rates/{currency}")
    String updateRate(
            @PathVariable String currency,
            @RequestParam String rateToPln,
            Authentication authentication,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {
        try {
            exchangeService.updateRate(
                    CurrencyCode.valueOf(currency.toUpperCase()),
                    new BigDecimal(rateToPln.strip().replace(',', '.'))
            );
            auditService.recordAdminAction(authentication.getName(), "ADMIN_RATE_UPDATE", null, null,
                    "Zaktualizowano kurs waluty " + currency.toUpperCase() + ".", true,
                    request.getRemoteAddr());
            redirectAttributes.addFlashAttribute("adminSuccess", "Kurs waluty zostal zaktualizowany.");
        } catch (IllegalArgumentException | ExchangeException exception) {
            auditService.recordAdminAction(authentication.getName(), "ADMIN_RATE_UPDATE", null, null,
                    exception.getMessage(), false, request.getRemoteAddr());
            redirectAttributes.addFlashAttribute("adminError", "Nieprawidlowy kurs waluty.");
        }
        return "redirect:/admin";
    }

    @PostMapping("/accounts/{accountId}/block")
    String blockAccount(@PathVariable Long accountId, Authentication authentication,
                        HttpServletRequest request, RedirectAttributes redirectAttributes) {
        try {
            adminService.blockAccount(accountId, authentication.getName(), request.getRemoteAddr());
            redirectAttributes.addFlashAttribute("adminSuccess", "Rachunek zostal zablokowany.");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("adminError", exception.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/accounts/{accountId}/activate")
    String activateAccount(@PathVariable Long accountId, Authentication authentication,
                           HttpServletRequest request, RedirectAttributes redirectAttributes) {
        try {
            adminService.activateAccount(accountId, authentication.getName(), request.getRemoteAddr());
            redirectAttributes.addFlashAttribute("adminSuccess", "Rachunek zostal aktywowany.");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("adminError", exception.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/accounts/{accountId}/close")
    String closeAccount(@PathVariable Long accountId, Authentication authentication,
                        HttpServletRequest request, RedirectAttributes redirectAttributes) {
        try {
            adminService.closeAccount(accountId, authentication.getName(), request.getRemoteAddr());
            redirectAttributes.addFlashAttribute("adminSuccess", "Rachunek zostal zamkniety. Dane zostaly zachowane.");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("adminError", exception.getMessage());
        }
        return "redirect:/admin";
    }

    @PostMapping("/accounts/{accountId}/logout-sessions")
    String logoutSessions(@PathVariable Long accountId, Authentication authentication,
                          HttpServletRequest request, RedirectAttributes redirectAttributes) {
        try {
            var removed = adminService.logoutSessions(accountId, authentication.getName(), request.getRemoteAddr());
            redirectAttributes.addFlashAttribute("adminSuccess",
                    removed == 0 ? "Nie znaleziono aktywnych sesji." : "Wylogowano sesje uzytkownika: " + removed + ".");
        } catch (IllegalArgumentException exception) {
            redirectAttributes.addFlashAttribute("adminError", exception.getMessage());
        }
        return "redirect:/admin";
    }

    @GetMapping("/data-retention")
    String retention(Model model, Authentication authentication) {
        model.addAttribute("retentionRuns", retentionExecutionService.recentRuns());
        model.addAttribute("retentionEnabled", retentionProperties.isEnabled());
        model.addAttribute("retentionDryRun", retentionProperties.isDryRun());
        model.addAttribute("retentionSchedule", retentionProperties.getSchedule());
        model.addAttribute("retentionBatchSize", retentionProperties.getBatchSize());
        model.addAttribute("retentionLoginHistoryDays", retentionProperties.getLoginHistoryDays());
        model.addAttribute("retentionNotificationDays", retentionProperties.getNotificationDays());
        model.addAttribute("retentionOperationAuditDays", retentionProperties.getOperationAuditDays());
        model.addAttribute("retentionAdminAuditDays", retentionProperties.getAdminAuditDays());
        model.addAttribute("retentionExcludedData", "karty, historia kart, rachunki, transakcje, zamowienia i refundacje");
        FrontendController.addCommonModel(model, dashboardService.dashboardFor(authentication.getName()));
        return "admin/data-retention";
    }

    @PostMapping("/data-retention/dry-run")
    String retentionDryRun(Authentication authentication, HttpServletRequest request,
                           RedirectAttributes redirectAttributes) {
        var result = retentionExecutionService.execute(true);
        auditService.recordAdminAction(authentication.getName(), "ADMIN_RETENTION_DRY_RUN", null, null,
                "Podglad retencji: " + result.deletedCount() + " rekordow.",
                result.errorCount() == 0, request.getRemoteAddr());
        redirectAttributes.addFlashAttribute("adminSuccess",
                "Podglad retencji zakonczony. Przetworzono potencjalnie " + result.deletedCount() + " rekordow.");
        return "redirect:/admin/data-retention";
    }

    @PostMapping("/data-retention/run")
    String retentionRun(Authentication authentication, HttpServletRequest request,
                         RedirectAttributes redirectAttributes) {
        if (!retentionProperties.isEnabled()) {
            redirectAttributes.addFlashAttribute("adminError",
                    "Retencja jest wylaczona konfiguracja RETENTION_ENABLED=false.");
            return "redirect:/admin/data-retention";
        }
        var result = retentionExecutionService.execute(false);
        auditService.recordAdminAction(authentication.getName(), "ADMIN_RETENTION_RUN", null, null,
                "Uruchomiono retencje: " + result.deletedCount() + " usunietych rekordow.",
                result.errorCount() == 0, request.getRemoteAddr());
        redirectAttributes.addFlashAttribute(
                result.errorCount() == 0 ? "adminSuccess" : "adminError",
                result.errorCount() == 0
                        ? "Retencja zakonczona. Usunieto " + result.deletedCount() + " rekordow."
                        : "Retencja zakonczona z bledami. Sprawdz historie uruchomien."
        );
        return "redirect:/admin/data-retention";
    }
}
