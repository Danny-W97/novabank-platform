package org.demo.bankdemo.web;

import org.demo.bankdemo.payment.authorization.PaymentAuthorizationService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/shop/authorize")
public class PaymentAuthorizationController {

    private final PaymentAuthorizationService service;

    public PaymentAuthorizationController(PaymentAuthorizationService service) {
        this.service = service;
    }

    @GetMapping("/{authorizationId}")
    String display(@PathVariable String authorizationId, Model model) {
        model.addAttribute("authorization", service.findForDisplay(authorizationId));
        return "payment/authorize";
    }

    @PostMapping("/{authorizationId}/approve")
    String approve(@PathVariable String authorizationId, Authentication authentication, Model model) {
        return redirectPage(service.approve(authorizationId, authentication.getName()), model);
    }

    @PostMapping("/{authorizationId}/reject")
    String reject(@PathVariable String authorizationId, Model model) {
        return redirectPage(service.reject(authorizationId), model);
    }

    private static String redirectPage(String targetUrl, Model model) {
        model.addAttribute("targetUrl", targetUrl);
        return "payment/redirect";
    }
}
