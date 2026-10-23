package org.demo.bankdemo.web;

import org.demo.bankdemo.dashboard.DashboardService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/** Shared profile page chrome only; authorization and business transactions remain elsewhere. */
@Component
public class ProfilePageModel {
    private final DashboardService dashboardService;
    public ProfilePageModel(DashboardService dashboardService) {
        this.dashboardService = java.util.Objects.requireNonNull(dashboardService);
    }
    public void add(Authentication authentication, Model model) {
        FrontendController.addCommonModel(model, dashboardService.dashboardFor(authentication.getName()));
    }
}
