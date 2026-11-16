package org.demo.bankdemo.web;

import org.demo.bankdemo.dashboard.DashboardService;
import org.demo.bankdemo.notification.NotificationService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final DashboardService dashboardService;

    public NotificationController(
            NotificationService notificationService,
            DashboardService dashboardService
    ) {
        this.notificationService = notificationService;
        this.dashboardService = dashboardService;
    }

    @GetMapping
    String list(Authentication authentication, Model model) {
        model.addAttribute("notificationPage", notificationService.notificationsFor(authentication.getName()));
        FrontendController.addCommonModel(
                model,
                dashboardService.dashboardFor(authentication.getName())
        );
        return "notifications/list";
    }

    @PostMapping("/{id}/read")
    String markRead(@PathVariable Long id, Authentication authentication) {
        notificationService.markRead(authentication.getName(), id);
        return "redirect:/notifications";
    }
}
