package org.demo.bankdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@ConfigurationProperties(prefix = "app.payment-api")
public class PaymentApiProperties {

    private String apiKey = "";
    private String apiKeys = "";
    private int rateLimitPerMinute = 60;
    private String keyMerchants = "";
    private String merchants = "";

    public String getKeyMerchants() { return keyMerchants; }
    public void setKeyMerchants(String value) { keyMerchants = value; }
    public String getMerchants() { return merchants; }
    public void setMerchants(String value) { merchants = value; }

    /** Non-secret operator configuration; key rotation must retain the same merchant ID. */
    public org.demo.bankdemo.payment.MerchantIdentity merchantForKey(String keyId) {
        var bindings = assignments(keyMerchants);
        var merchantId = bindings.get(keyId);
        var accounts = assignments(merchants).get(merchantId);
        if (merchantId == null || !merchantId.matches("[A-Za-z0-9_-]{1,64}")
                || accounts == null || accounts.isBlank()) {
            throw new IllegalStateException("Payment merchant mapping is not configured.");
        }
        var allowed = Arrays.stream(accounts.split("\\+", -1)).collect(Collectors.toUnmodifiableSet());
        if (allowed.stream().anyMatch(account -> !account.matches("\\d{26}"))) {
            throw new IllegalStateException("Payment merchant account mapping is invalid.");
        }
        return new org.demo.bankdemo.payment.MerchantIdentity(merchantId, allowed);
    }

    private static Map<String, String> assignments(String value) {
        var result = new LinkedHashMap<String, String>();
        if (value == null || value.isBlank()) return result;
        for (var entry : value.split("[;,]", -1)) {
            var parts = entry.split("=", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()
                    || result.putIfAbsent(parts[0].strip(), parts[1].strip()) != null) {
                throw new IllegalStateException("Payment merchant mapping is invalid or duplicated.");
            }
        }
        return result;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(String apiKeys) {
        this.apiKeys = apiKeys;
    }

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public List<ApiKeyDefinition> configuredKeys() {
        var definitions = new LinkedHashMap<String, ApiKeyDefinition>();
        if (apiKeys != null && !apiKeys.isBlank()) {
            for (String rawDefinition : apiKeys.split(";")) {
                var definition = parse(rawDefinition);
                if (definitions.putIfAbsent(definition.id(), definition) != null) {
                    throw new IllegalStateException("BANK_API_KEYS zawiera zduplikowane id klucza: "
                            + definition.id());
                }
            }
        }
        if (apiKey != null && !apiKey.isBlank()) {
            definitions.putIfAbsent("legacy", new ApiKeyDefinition(
                    "legacy", apiKey, Set.of("payments:write", "payments:authorize", "payments:status", "payments:refund", "payments:card"), Instant.MAX
            ));
        }
        return List.copyOf(definitions.values());
    }

    private static ApiKeyDefinition parse(String rawDefinition) {
        var parts = rawDefinition.strip().split("\\|", -1);
        if (parts.length != 4 || Arrays.stream(parts).anyMatch(String::isBlank)) {
            throw new IllegalStateException(
                    "BANK_API_KEYS musi miec format id|secret|scope1,scope2|expiresAt"
            );
        }
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(parts[3].strip());
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("Nieprawidlowy termin wygasniecia klucza API.", exception);
        }
        var scopes = Arrays.stream(parts[2].split(","))
                .map(String::strip)
                .filter(scope -> !scope.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        if (scopes.isEmpty()) {
            throw new IllegalStateException("Klucz API musi miec co najmniej jeden zakres.");
        }
        return new ApiKeyDefinition(parts[0].strip(), parts[1], scopes, expiresAt);
    }

    public record ApiKeyDefinition(String id, String secret, Set<String> scopes, Instant expiresAt) {

        public boolean isUsableAt(Instant now) {
            return expiresAt.isAfter(now);
        }

        public boolean allows(String scope) {
            return scopes.contains(scope);
        }
    }
}
