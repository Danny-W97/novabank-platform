package org.demo.bankdemo.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.demo.bankdemo.audit.LoginHistoryService;
import org.demo.bankdemo.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginFailureHandlerTest {

    @Test
    void alertsUserWhenFailureThresholdIsReached() throws Exception {
        var attempts = mock(LoginAttemptService.class);
        var history = mock(LoginHistoryService.class);
        var notifications = mock(NotificationService.class);
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        when(request.getParameter("username")).thenReturn("anna@example.pl");
        when(attempts.recordFailureCount("anna@example.pl")).thenReturn(LoginAttemptService.MAX_FAILURES);

        var handler = new LoginFailureHandler(attempts, history, notifications);

        handler.onAuthenticationFailure(request, response, new BadCredentialsException("bad"));

        verify(notifications).securityEvent(eq("anna@example.pl"), contains("5 kolejnych"));
        verify(history).record(eq("anna@example.pl"), eq(request), eq(false), contains("Nieudane"));
    }
}
