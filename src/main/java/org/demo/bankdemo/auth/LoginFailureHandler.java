package org.demo.bankdemo.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.demo.bankdemo.audit.LoginHistoryService;
import org.demo.bankdemo.notification.NotificationService;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Component
public class LoginFailureHandler implements AuthenticationFailureHandler {

    private static final String PAYMENT_CONTINUATION_PATTERN = "^/shop/authorize/[A-Za-z0-9_-]+$";

    private final LoginAttemptService loginAttemptService;
    private final LoginHistoryService loginHistoryService;
    private final NotificationService notificationService;

    public LoginFailureHandler(
            LoginAttemptService loginAttemptService,
            LoginHistoryService loginHistoryService,
            NotificationService notificationService
    ) {
        this.loginAttemptService = loginAttemptService;
        this.loginHistoryService = loginHistoryService;
        this.notificationService = notificationService;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException, ServletException {
        var username = request.getParameter("username");
        var failures = loginAttemptService.recordFailureCount(username);
        if (failures == LoginAttemptService.MAX_FAILURES) {
            notificationService.securityEvent(
                    username,
                    "Wykryto " + LoginAttemptService.MAX_FAILURES
                            + " kolejnych nieudanych prob logowania. Konto zostalo czasowo zabezpieczone."
            );
        }
        loginHistoryService.record(
                username,
                request,
                false,
                "Nieudane uwierzytelnienie"
        );
        var builder = UriComponentsBuilder.fromPath("/login").queryParam("error");
        var continueUrl = request.getParameter("continue");
        if (continueUrl != null && continueUrl.matches(PAYMENT_CONTINUATION_PATTERN)) {
            builder.queryParam("continue", continueUrl);
        }
        response.sendRedirect(builder.build().encode().toUriString());
    }
}
