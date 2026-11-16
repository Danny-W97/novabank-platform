package org.demo.bankdemo.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.demo.bankdemo.audit.LoginHistoryService;
import org.demo.bankdemo.profile.TwoFactorService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.regex.Pattern;

@Component
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final Pattern PAYMENT_CONTINUATION =
            Pattern.compile("^/shop/authorize/[A-Za-z0-9_-]+$");

    private final LoginService loginService;
    private final LoginAttemptService loginAttemptService;
    private final DefaultRedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
    private final RequestCache requestCache;
    private final TwoFactorService twoFactorService;
    private final TwoFactorLoginService twoFactorLoginService;
    private final SecurityContextRepository securityContextRepository;
    private final LoginHistoryService loginHistoryService;

    public LoginSuccessHandler(
            LoginService loginService,
            LoginAttemptService loginAttemptService,
            RequestCache requestCache,
            TwoFactorService twoFactorService,
            TwoFactorLoginService twoFactorLoginService,
            SecurityContextRepository securityContextRepository,
            LoginHistoryService loginHistoryService
    ) {
        this.loginService = loginService;
        this.loginAttemptService = loginAttemptService;
        this.requestCache = requestCache;
        this.twoFactorService = twoFactorService;
        this.twoFactorLoginService = twoFactorLoginService;
        this.securityContextRepository = securityContextRepository;
        this.loginHistoryService = loginHistoryService;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {
        var savedRequest = requestCache.getRequest(request, response);
        var explicitTarget = request.getParameter("continue");
        var targetUrl = isSafePaymentContinuation(explicitTarget)
                ? explicitTarget
                : savedRequest == null ? "/dashboard" : savedRequest.getRedirectUrl();
        requestCache.removeRequest(request, response);

        if (twoFactorService.isEnabled(authentication.getName())) {
            twoFactorLoginService.start(request, authentication.getName(), targetUrl);
            SecurityContextHolder.clearContext();
            securityContextRepository.saveContext(
                    SecurityContextHolder.createEmptyContext(),
                    request,
                    response
            );
            redirectStrategy.sendRedirect(request, response, "/login/2fa");
            return;
        }

        loginAttemptService.clear(authentication.getName());
        loginService.recordSuccessfulLogin(authentication.getName());
        loginHistoryService.record(authentication.getName(), request, true, null);
        redirectStrategy.sendRedirect(request, response, targetUrl);
    }

    private static boolean isSafePaymentContinuation(String targetUrl) {
        return targetUrl != null && PAYMENT_CONTINUATION.matcher(targetUrl).matches();
    }
}
