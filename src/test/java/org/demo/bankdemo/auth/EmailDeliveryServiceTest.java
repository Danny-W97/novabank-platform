package org.demo.bankdemo.auth;

import org.demo.bankdemo.config.EmailProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class EmailDeliveryServiceTest {
    @SuppressWarnings("unchecked")
    ObjectProvider<JavaMailSender> provider() { return mock(ObjectProvider.class); }

    @Test void prodRejectsLogModeEvenWhenDevIsAlsoActive(CapturedOutput output) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "prod");
        var service = new EmailDeliveryService(new EmailProperties(), provider(), environment);
        assertThatThrownBy(() -> service.sendPasswordReset("synthetic@example.invalid", "synthetic-reset-token"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(output.getAll()).doesNotContain("synthetic-reset-token", "synthetic@example.invalid");
    }

    @Test void defaultProfileCannotLogEmailBody(CapturedOutput output) {
        var service = new EmailDeliveryService(new EmailProperties(), provider(), new MockEnvironment());
        assertThatThrownBy(() -> service.sendVerification("synthetic@example.invalid", "synthetic-verification-token"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(output.getAll()).doesNotContain("synthetic-verification-token", "synthetic@example.invalid");
    }

    @Test void smtpSendsWithoutPrintingToken(CapturedOutput output) {
        var properties = new EmailProperties(); properties.setMode("smtp");
        var provider = provider(); var sender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        new EmailDeliveryService(properties, provider, new MockEnvironment())
                .sendPasswordReset("synthetic@example.invalid", "synthetic-reset-token");
        verify(sender).send(any(SimpleMailMessage.class));
        assertThat(output.getAll()).doesNotContain("synthetic-reset-token", "synthetic@example.invalid");
    }

    @Test void isolatedDevRetainsExplicitLogMode(CapturedOutput output) {
        var environment = new MockEnvironment(); environment.setActiveProfiles("dev");
        new EmailDeliveryService(new EmailProperties(), provider(), environment)
                .sendVerification("synthetic@example.invalid", "synthetic-verification-token");
        assertThat(output.getOut()).contains("[EMAIL-DEV]", "synthetic-verification-token");
    }
}
