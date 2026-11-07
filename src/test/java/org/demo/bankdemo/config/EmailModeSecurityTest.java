package org.demo.bankdemo.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class EmailModeSecurityTest {
    @Test void invalidModeIsNotTreatedAsLog() {
        assertThatThrownBy(() -> new EmailProperties().setMode("smpt"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LOG albo SMTP");
    }

    @Test void productionRequiresExplicitSmtp() {
        for (var mode : java.util.List.of("log", "smpt", "")) {
            assertThatThrownBy(() -> ProductionConfigurationValidator.validateEmailMode(
                    new MockEnvironment().withProperty("app.email.mode", mode)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("SMTP");
        }
        assertThatThrownBy(() -> ProductionConfigurationValidator.validateEmailMode(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class);
        ProductionConfigurationValidator.validateEmailMode(new MockEnvironment().withProperty("app.email.mode", "SMTP"));
    }

    @Test void productionRejectsLocalMailpitAndRequiresRealSmtpHost() {
        for (var host : java.util.List.of("", "localhost", "127.0.0.1", "bank-mailpit", "mailpit.local")) {
            assertThatThrownBy(() -> ProductionConfigurationValidator.validateProductionSmtp(
                    new MockEnvironment().withProperty("MAIL_HOST", host).withProperty("MAIL_PORT", "587")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("rzeczywistego serwera SMTP");
        }
        assertThatThrownBy(() -> ProductionConfigurationValidator.validateProductionSmtp(
                new MockEnvironment().withProperty("MAIL_HOST", "smtp.novabank.pl").withProperty("MAIL_PORT", "11026")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("11026");
        ProductionConfigurationValidator.validateProductionSmtp(
                new MockEnvironment().withProperty("MAIL_HOST", "smtp.novabank.pl").withProperty("MAIL_PORT", "587"));
    }
}
