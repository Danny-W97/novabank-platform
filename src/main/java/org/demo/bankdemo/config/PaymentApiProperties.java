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
