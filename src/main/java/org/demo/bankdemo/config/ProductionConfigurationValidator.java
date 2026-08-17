package org.demo.bankdemo.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Fails fast before database beans are created when production secrets are missing. */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
public class ProductionConfigurationValidator {

    @Bean
    static BeanFactoryPostProcessor productionSecretValidator(Environment environment) {
        return beanFactory -> validate(environment);
    }

    private static void validate(Environment environment) {
        requireEnv(environment, "POSTGRES_HOST");
        requireEnv(environment, "POSTGRES_PORT");
        requireEnv(environment, "POSTGRES_DB");
        requireEnv(environment, "POSTGRES_USER");
        requireEnv(environment, "POSTGRES_PASSWORD");
        requireEnv(environment, "REDIS_HOST");
        requireEnv(environment, "REDIS_PORT");
        requireEnv(environment, "BANK_API_URL");
        requireEnv(environment, "SHOP_PAYMENT_CALLBACK_URL");
        requireEnv(environment, "ADMIN_EMAIL");
        requireEnv(environment, "BANK_API_KEYS");
        requireEnv(environment, "TOTP_ENCRYPTION_KEY");
        requireEnv(environment, "METRICS_USERNAME");
        requireEnv(environment, "METRICS_PASSWORD");
        requireVaultConfiguration(environment);
        requireTlsConfiguration(environment);

        requireHttps(environment, "BANK_API_URL");
        requireHttps(environment, "SHOP_PAYMENT_CALLBACK_URL");
        if (!environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) {
            throw new IllegalStateException(
                    "Profil prod wymaga server.servlet.session.cookie.secure=true.");
        }
    }

    private static void requireEnv(Environment environment, String variable) {
        var value = environment.getProperty(variable);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Brak wymaganej zmiennej produkcyjnej: " + variable);
        }
    }

    private static void requireHttps(Environment environment, String variable) {
        var value = environment.getProperty(variable, "");
        if (!value.startsWith("https://")) {
            throw new IllegalStateException("Zmienna produkcyjna musi uzywac HTTPS: " + variable);
        }
    }

    private static void requireVaultConfiguration(Environment environment) {
        requireEnv(environment, "VAULT_ADDR");
        requireEnv(environment, "VAULT_ROLE");
        requireEnv(environment, "VAULT_ROLE_ID");
        requireEnv(environment, "VAULT_SECRET_ID");
        requireEnv(environment, "VAULT_SSL_TRUST_STORE");
        requireEnv(environment, "VAULT_SSL_TRUST_STORE_PASSWORD");
        requireHttps(environment, "VAULT_ADDR");
    }

    private static void requireTlsConfiguration(Environment environment) {
        var termination = environment.getProperty("TLS_TERMINATION", "").strip().toLowerCase();
        if (!termination.equals("proxy") && !termination.equals("app")) {
            throw new IllegalStateException(
                    "TLS_TERMINATION musi miec wartosc proxy albo app w profilu prod.");
        }

        var sslEnabled = Boolean.parseBoolean(environment.getProperty("SERVER_SSL_ENABLED", "false"));
        if (termination.equals("app")) {
            if (!sslEnabled) {
                throw new IllegalStateException(
                        "Dla TLS_TERMINATION=app ustaw SERVER_SSL_ENABLED=true.");
            }
            requireEnv(environment, "SERVER_SSL_KEY_STORE");
            requireEnv(environment, "SERVER_SSL_KEY_STORE_PASSWORD");
            requireEnv(environment, "SERVER_SSL_KEY_ALIAS");
        } else if (sslEnabled) {
            throw new IllegalStateException(
                    "Dla TLS_TERMINATION=proxy ustaw SERVER_SSL_ENABLED=false.");
        }
    }
}
