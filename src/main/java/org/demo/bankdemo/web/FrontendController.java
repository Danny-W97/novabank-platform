package org.demo.bankdemo.web;

import org.demo.bankdemo.dashboard.DashboardService;
import org.demo.bankdemo.dashboard.DashboardSecurityService;
import org.demo.bankdemo.dashboard.DashboardView;
import org.demo.bankdemo.dashboard.ExpenseChartService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class FrontendController {

    private final DashboardService dashboardService;
    private final ExpenseChartService expenseChartService;
    private final DashboardSecurityService dashboardSecurityService;

    @Autowired
    public FrontendController(
            DashboardService dashboardService,
            ExpenseChartService expenseChartService,
            DashboardSecurityService dashboardSecurityService
    ) {
        this.dashboardService = dashboardService;
        this.expenseChartService = expenseChartService;
        this.dashboardSecurityService = dashboardSecurityService;
    }

    public FrontendController(
            DashboardService dashboardService,
            ExpenseChartService expenseChartService
    ) {
        this(dashboardService, expenseChartService, null);
    }

    @GetMapping("/")
    String home(Authentication authentication) {
        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            return "redirect:/dashboard";
        }
        return "redirect:/login";
    }

    @GetMapping("/about")
    String about() {
        return "about";
    }

    @GetMapping("/dashboard")
    String dashboard(
            Authentication authentication,
            Model model,
            @RequestParam(name = "period", defaultValue = "6") int requestedPeriod
    ) {
        var dashboard = dashboardService.dashboardFor(authentication.getName());
        var period = ExpenseChartService.normalizePeriod(requestedPeriod);
        addCommonModel(model, dashboard);
        model.addAttribute("transactions", dashboard.transactions());
        model.addAttribute("monthName", dashboard.monthName());
        model.addAttribute("lastActivity", dashboard.lastActivity());
        model.addAttribute("expenses", dashboard.expenses());
        model.addAttribute("expenseCount", dashboard.expenseCount());
        model.addAttribute("income", dashboard.income());
        model.addAttribute("incomeCount", dashboard.incomeCount());
        model.addAttribute("expenseChart", expenseChartService.expensesFor(authentication.getName(), period));
        model.addAttribute("expensePeriod", period);
        model.addAttribute("security", dashboardSecurityService == null
                ? DashboardSecurityService.DashboardSecurityView.empty()
                : dashboardSecurityService.forUser(authentication.getName()));
        return "dashboard/index";
    }

    String dashboard(Authentication authentication, Model model) {
        return dashboard(authentication, model, 6);
    }




    static void addCommonModel(Model model, DashboardView dashboard) {
        model.addAttribute("firstName", dashboard.firstName());
        model.addAttribute("fullName", dashboard.fullName());
        model.addAttribute("initials", dashboard.initials());
        model.addAttribute("email", dashboard.email());
        model.addAttribute("phone", dashboard.phone());
        model.addAttribute("customerNumber", dashboard.customerNumber());
        model.addAttribute("accountNumber", dashboard.accountNumber());
        model.addAttribute("balance", dashboard.balance());
        model.addAttribute("accountStatus", dashboard.accountStatus());
        model.addAttribute("currentDate", dashboard.currentDate());
    }
}
