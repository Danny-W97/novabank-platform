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
}
