package org.demo.bankdemo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

@Component
public class BankRateLimitFilter extends OncePerRequestFilter {

    private final DistributedRateLimitService rateLimitService;
    private final RateLimitProperties properties;

    @org.springframework.beans.factory.annotation.Autowired
    public BankRateLimitFilter(
            ObjectProvider<DistributedRateLimitService> rateLimitService,
            ObjectProvider<RateLimitProperties> properties
    ) {
        this(rateLimitService.getIfAvailable(), properties.getIfAvailable(RateLimitProperties::new));
    }

    BankRateLimitFilter(DistributedRateLimitService rateLimitService, RateLimitProperties properties) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (rateLimitService == null) {
            filterChain.doFilter(request, response);
            return;
        }
        var path = request.getRequestURI();
        if (!HttpMethod.POST.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        var remote = request.getRemoteAddr();
        if (isLogin(path)) {
            var email = request.getParameter("username");
            var accountDecision = email == null || email.isBlank()
                    ? new DistributedRateLimitService.Decision(true, 0, true)
                    : rateLimitService.tryAcquire(
                    "login-account-check",
                    email.strip().toLowerCase(),
                    properties.getLoginAccountPerWindow(),
                    Duration.ofSeconds(properties.getLoginWindowSeconds())
            );
            var ipDecision = rateLimitService.tryAcquire(
                    "login-ip-check",
                    remote,
                    properties.getLoginIpPerWindow(),
                    Duration.ofSeconds(properties.getLoginWindowSeconds())
            );
            if (!allow(accountDecision, ipDecision, response)) {
                return;
            }
        } else if (isAuthAction(path)) {
            var decision = rateLimitService.tryAcquire(
                    "auth-action-ip",
                    remote,
                    properties.getAuthActionIpPerWindow(),
                    Duration.ofSeconds(properties.getAuthActionWindowSeconds())
            );
            if (!allow(decision, response)) {
                return;
            }
        } else if (isTransfer(path)) {
            var user = request.getUserPrincipal() == null
                    ? "anonymous"
                    : request.getUserPrincipal().getName();
            var ipDecision = rateLimitService.tryAcquire(
                    "transfer-ip",
                    remote,
                    properties.getTransferIpPerWindow(),
                    Duration.ofMinutes(1)
            );
            var userDecision = rateLimitService.tryAcquire(
                    "transfer-user",
                    user,
                    properties.getTransferUserPerWindow(),
                    Duration.ofMinutes(1)
            );
            if (!allow(ipDecision, userDecision, response)) {
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isLogin(String path) {
        return "/login".equals(path) || "/login/2fa".equals(path);
    }

    private static boolean isAuthAction(String path) {
        return "/register".equals(path)
                || "/password/forgot".equals(path)
                || "/password/reset".equals(path);
    }

    private static boolean isTransfer(String path) {
        return "/transfer/confirm".equals(path) || "/transfer/execute".equals(path);
    }

    private static boolean allow(
            DistributedRateLimitService.Decision first,
            DistributedRateLimitService.Decision second,
            HttpServletResponse response
    ) throws IOException {
        if (!first.backendAvailable() || !second.backendAvailable()) {
            reject(response, 503, 30, "Ochrona rate limiting jest chwilowo niedostępna.");
            return false;
        }
        if (!first.allowed() || !second.allowed()) {
            reject(response, 429, Math.max(first.retryAfterSeconds(), second.retryAfterSeconds()),
                    "Przekroczono limit żądań. Spróbuj ponownie później.");
            return false;
        }
        return true;
    }

    private static boolean allow(DistributedRateLimitService.Decision decision, HttpServletResponse response)
            throws IOException {
        if (!decision.backendAvailable()) {
            reject(response, 503, decision.retryAfterSeconds(), "Ochrona rate limiting jest chwilowo niedostępna.");
            return false;
        }
        if (!decision.allowed()) {
            reject(response, 429, decision.retryAfterSeconds(),
                    "Przekroczono limit żądań. Spróbuj ponownie później.");
            return false;
        }
        return true;
    }

    private static void reject(HttpServletResponse response, int status, long retryAfter, String message)
            throws IOException {
        response.setHeader("Retry-After", Long.toString(Math.max(1, retryAfter)));
        response.sendError(status, message);
    }
}
