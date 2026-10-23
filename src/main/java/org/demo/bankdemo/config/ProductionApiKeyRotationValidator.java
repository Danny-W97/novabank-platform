package org.demo.bankdemo.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Ensures production starts with at least one currently usable key for each payment API scope. */
@Component
@Profile("prod")
public class ProductionApiKeyRotationValidator {

    public ProductionApiKeyRotationValidator(PaymentApiProperties properties) {
        var keys = properties.configuredKeys();
        var now = Instant.now();
        if (keys.isEmpty() || keys.stream().noneMatch(key -> key.isUsableAt(now))) {
            throw new IllegalStateException(
                    "BANK_API_KEYS musi zawierac co najmniej jeden niewygasly klucz.");
        }
        if (keys.stream().noneMatch(key -> key.isUsableAt(now) && key.allows("payments:authorize"))) {
            throw new IllegalStateException(
                    "BANK_API_KEYS musi zawierac aktywny zakres payments:authorize.");
        }
        if (keys.stream().noneMatch(key -> key.isUsableAt(now) && key.allows("payments:status"))) {
            throw new IllegalStateException(
                    "BANK_API_KEYS musi zawierac aktywny zakres payments:status.");
        }
        if (keys.stream().noneMatch(key -> key.isUsableAt(now) && key.allows("payments:refund"))) {
            throw new IllegalStateException(
                    "BANK_API_KEYS musi zawierac aktywny zakres payments:refund.");
        }
    }
}
