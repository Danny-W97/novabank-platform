package org.demo.shopapp.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

@Component
public class ShopRateLimitFilter extends OncePerRequestFilter {

    private final ShopDistributedRateLimitService rateLimitService;
    private final ShopRateLimitProperties properties;

    public ShopRateLimitFilter(
            ShopDistributedRateLimitService rateLimitService,
            ShopRateLimitProperties properties
    ) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!properties.isEnabled() || !HttpMethod.POST.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        var path = request.getRequestURI();
        var remote = request.getRemoteAddr();
        var identity = identity(request);
        if ("login".equals(scope(path))) {
            var account = request.getParameter("username");
            var accountDecision = account == null || account.isBlank()
                    ? new ShopDistributedRateLimitService.Decision(true, 0, true)
                    : rateLimitService.tryAcquire(
                    "login-account", account.strip().toLowerCase(),
                    properties.getLoginAccountPerWindow(), window()
            );
            var ipDecision = rateLimitService.tryAcquire(
                    "login-ip", remote, properties.getLoginIpPerWindow(), window()
            );
            if (!allow(accountDecision, ipDecision, response)) {
                return;
            }
        } else if ("checkout".equals(scope(path))) {
            if (!allow(
                    rateLimitService.tryAcquire("checkout-ip", remote, properties.getCheckoutIpPerWindow(), window()),
                    rateLimitService.tryAcquire("checkout-user", identity, properties.getCheckoutUserPerWindow(), window()),
                    response
            )) {
                return;
            }
        } else if ("payment".equals(scope(path))) {
            if (!allow(
                    rateLimitService.tryAcquire("payment-ip", remote, properties.getPaymentIpPerWindow(), window()),
                    rateLimitService.tryAcquire("payment-user", identity, properties.getPaymentUserPerWindow(), window()),
                    response
            )) {
                return;
            }
        } else if ("webhook".equals(scope(path))) {
            if (!allow(rateLimitService.tryAcquire(
                    "stripe-webhook-ip", remote, properties.getWebhookIpPerWindow(), window()), response)) {
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private Duration window() {
        return Duration.ofSeconds(properties.getWindowSeconds());
    }

    private static String scope(String path) {
        if ("/login".equals(path)) {
            return "login";
        }
        if ("/api/auth/register".equals(path)) {
            return "login";
        }
        if ("/api/orders".equals(path)) {
            return "checkout";
        }
        if (path.startsWith("/api/checkout/") || path.endsWith("/card-pay")
                || path.endsWith("/card-checkout") || path.endsWith("/pay")
                || path.endsWith("/refund")) {
            return "payment";
        }
        if ("/api/payments/stripe/webhook".equals(path)) {
            return "webhook";
        }
        return "";
    }

    private static String identity(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            return authentication.getName();
        }
        return "anonymous:" + request.getRemoteAddr();
    }

    private static boolean allow(
            ShopDistributedRateLimitService.Decision first,
            ShopDistributedRateLimitService.Decision second,
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

    private static boolean allow(
            ShopDistributedRateLimitService.Decision decision,
            HttpServletResponse response
    ) throws IOException {
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
