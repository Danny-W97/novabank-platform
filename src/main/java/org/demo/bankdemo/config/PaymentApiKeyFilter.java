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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PaymentApiKeyFilter extends OncePerRequestFilter {

    private static final String PAYMENT_API_PATH = "/api/payments";
    private static final String AUTHORIZATION_API_PATH = "/api/payment-authorizations";
    private static final String REFUND_API_PATH = "/api/payments/refunds";
    private static final String CARD_PAYMENT_API_PATH = "/api/card-payments";
    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String API_KEY_ID_HEADER = "X-API-Key-Id";

    private final PaymentApiProperties properties;
    private final DistributedRateLimitService distributedRateLimitService;
    private final Map<String, RateWindow> rateWindows = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentApiKeyFilter(
            PaymentApiProperties properties,
            ObjectProvider<DistributedRateLimitService> distributedRateLimitService
    ) {
        this.properties = properties;
        this.distributedRateLimitService = distributedRateLimitService.getIfAvailable();
    }

    PaymentApiKeyFilter(PaymentApiProperties properties) {
        this.properties = properties;
        this.distributedRateLimitService = null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        var path = request.getRequestURI();
        if (!HttpMethod.POST.matches(request.getMethod())
                && !(HttpMethod.GET.matches(request.getMethod()) && isStatusPath(path))) {
            filterChain.doFilter(request, response);
            return;
        }

        var requiredScope = requiredScope(request.getMethod(), path);
        if (requiredScope == null) {
            filterChain.doFilter(request, response);
            return;
        }

        var providedKey = request.getHeader(API_KEY_HEADER);
        var configuredKeys = properties.configuredKeys();
        if (configuredKeys.isEmpty()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Payment API is not configured.");
            return;
        }
        var selectedKey = selectKey(configuredKeys, request.getHeader(API_KEY_ID_HEADER), providedKey);
        if (selectedKey == null || !selectedKey.isUsableAt(Instant.now())) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                    "Missing, invalid or expired API key.");
            return;
        }
        if (!selectedKey.allows(requiredScope)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "API key does not have the required scope.");
            return;
        }
        var rateDecision = allowRequest(selectedKey.id(), request.getRemoteAddr());
        if (!rateDecision.backendAvailable()) {
            response.setHeader("Retry-After", Long.toString(rateDecision.retryAfterSeconds()));
            response.sendError(503, "Payment API rate limiting is temporarily unavailable.");
            return;
        }
        if (!rateDecision.allowed()) {
            response.setHeader("Retry-After", Long.toString(rateDecision.retryAfterSeconds()));
            response.sendError(429, "API rate limit exceeded.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static String requiredScope(String method, String path) {
        if (HttpMethod.GET.matches(method) && isStatusPath(path)) {
            return "payments:status";
        }
        if (PAYMENT_API_PATH.equals(path)) {
            return "payments:write";
        }
        if (REFUND_API_PATH.equals(path)) {
            return "payments:refund";
        }
        if (CARD_PAYMENT_API_PATH.equals(path) || path.equals(CARD_PAYMENT_API_PATH + "/quote")) {
            return "payments:card";
        }
        if (AUTHORIZATION_API_PATH.equals(path)
                || (path.startsWith(AUTHORIZATION_API_PATH + "/") && path.endsWith("/redeem"))) {
            return "payments:authorize";
        }
        return null;
    }

    private static boolean isStatusPath(String path) {
        return path.startsWith(AUTHORIZATION_API_PATH + "/") && path.endsWith("/status");
    }

    private static boolean sameKey(String providedKey, String expectedKey) {
        return MessageDigest.isEqual(
                providedKey.getBytes(StandardCharsets.UTF_8),
                expectedKey.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static PaymentApiProperties.ApiKeyDefinition selectKey(
            java.util.List<PaymentApiProperties.ApiKeyDefinition> keys,
            String requestedId,
            String providedKey
    ) {
        if (providedKey == null || providedKey.isBlank()) {
            return null;
        }
        return keys.stream()
                .filter(key -> requestedId == null || requestedId.isBlank() || key.id().equals(requestedId))
                .filter(key -> sameKey(providedKey, key.secret()))
                .findFirst()
                .orElse(null);
    }

    private DistributedRateLimitService.Decision allowRequest(String keyId, String remoteAddress) {
        var limit = properties.getRateLimitPerMinute();
        if (limit <= 0) {
            return new DistributedRateLimitService.Decision(false, 60, true);
        }
        if (distributedRateLimitService != null) {
            return distributedRateLimitService.tryAcquire(
                    "payment-api:" + keyId,
                    remoteAddress == null ? "unknown" : remoteAddress,
                    limit,
                    java.time.Duration.ofMinutes(1)
            );
        }
        var bucketKey = keyId + "@" + (remoteAddress == null ? "unknown" : remoteAddress);
        var now = Instant.now();
        var window = rateWindows.computeIfAbsent(bucketKey, ignored -> new RateWindow(now));
        synchronized (window) {
            if (now.isAfter(window.startedAt.plusSeconds(60))) {
                window.startedAt = now;
                window.count = 0;
            }
            if (window.count >= limit) {
                return new DistributedRateLimitService.Decision(false, 60, true);
            }
            window.count++;
            return new DistributedRateLimitService.Decision(true, 60, true);
        }
    }

    private static final class RateWindow {
        private Instant startedAt;
        private int count;

        private RateWindow(Instant startedAt) {
            this.startedAt = startedAt;
        }
    }
}
