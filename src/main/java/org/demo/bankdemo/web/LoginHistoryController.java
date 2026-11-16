package org.demo.bankdemo.web;

import org.demo.bankdemo.audit.LoginHistoryService;
import org.demo.bankdemo.dashboard.DashboardService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/profile/login-history")
public class LoginHistoryController {

    private final DashboardService dashboardService;
    private final LoginHistoryService loginHistoryService;

    public LoginHistoryController(
            DashboardService dashboardService,
            LoginHistoryService loginHistoryService
    ) {
        this.dashboardService = dashboardService;
        this.loginHistoryService = loginHistoryService;
    }

    @GetMapping
    String view(Authentication authentication, Model model) {
        var dashboard = dashboardService.dashboardFor(authentication.getName());
        FrontendController.addCommonModel(model, dashboard);
        model.addAttribute("loginHistory", loginHistoryService.historyFor(authentication.getName()));
        return "profile/login-history";
    }
}
