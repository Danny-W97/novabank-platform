package org.demo.bankdemo.auth;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.demo.bankdemo.config.EmailProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;

import java.util.Properties;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class EmailDeliveryServiceTest {
    @SuppressWarnings("unchecked")
    ObjectProvider<JavaMailSender> provider() { return mock(ObjectProvider.class); }

    @Test void prodRejectsLogModeEvenWhenDevIsAlsoActive(CapturedOutput output) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "prod");
        var properties = new EmailProperties();
        properties.setMode("log");
        var service = new EmailDeliveryService(properties, provider(), environment);
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

    @Test void isolatedDevRejectsLogModeAndNeverPrintsTokens(CapturedOutput output) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        var properties = new EmailProperties();
        properties.setMode("log");
        var service = new EmailDeliveryService(properties, provider(), environment);
        assertThatThrownBy(() -> service.sendVerification("synthetic@example.invalid", "synthetic-verification-token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wyłączone");
        assertThat(output.getAll()).doesNotContain("[EMAIL-DEV]", "synthetic-verification-token", "synthetic@example.invalid");
    }

    @Test void smtpSendsWithoutPrintingToken(CapturedOutput output) {
        var properties = new EmailProperties();
        properties.setMode("smtp");
        var provider = provider();
        var sender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        new EmailDeliveryService(properties, provider, new MockEnvironment())
                .sendPasswordReset("synthetic@example.invalid", "synthetic-reset-token");
        verify(sender).send(any(SimpleMailMessage.class));
        assertThat(output.getAll()).doesNotContain("synthetic-reset-token", "synthetic@example.invalid");
    }

    @Test void smtpVerificationContainsActivateAccountLinkAnd24hValidity(CapturedOutput output) throws Exception {
        var properties = new EmailProperties();
        properties.setMode("smtp");
        properties.setBaseUrl("http://localhost:8080");
        var provider = provider();
        var sender = mock(JavaMailSender.class);
        var mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(mimeMessage);
        when(provider.getIfAvailable()).thenReturn(sender);

        new EmailDeliveryService(properties, provider, new MockEnvironment())
                .sendVerification("klient@gmail.com", "jednorazowy-token-123");

        var captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        var sent = captor.getValue();
        assertThat(sent.getSubject()).isEqualTo("Potwierdź adres e-mail NovaBank");
        var rawOut = new java.io.ByteArrayOutputStream();
        sent.writeTo(rawOut);
        var rawMime = rawOut.toString(java.nio.charset.StandardCharsets.UTF_8)
                .replace("=\r\n", "")
                .replace("=\n", "")
                .replace("=3D", "=");
        assertThat(rawMime)
                .contains("Aktywuj konto bankowe")
                .contains("http://localhost:8080/verify-email?token=jednorazowy-token-123")
                .contains("24 godziny");
        assertThat(output.getAll()).doesNotContain("jednorazowy-token-123");
    }

    @Test void smtpFailureThrowsEmailDeliveryExceptionWithoutLoggingToken(CapturedOutput output) {
        var properties = new EmailProperties();
        properties.setMode("smtp");
        var provider = provider();
        var sender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        doThrow(new MailSendException("Connection refused: localhost:11026"))
                .when(sender).send(any(SimpleMailMessage.class));

        var service = new EmailDeliveryService(properties, provider, new MockEnvironment());
        assertThatThrownBy(() -> service.sendVerification("synthetic@example.invalid", "secret-token-xyz"))
                .isInstanceOf(EmailDeliveryException.class);
        assertThat(output.getAll()).doesNotContain("secret-token-xyz", "synthetic@example.invalid");
    }

    @Test void noopModeIsRestrictedToIsolatedTestProfile(CapturedOutput output) {
        var properties = new EmailProperties();
        properties.setMode("noop");

        var testEnv = new MockEnvironment();
        testEnv.setActiveProfiles("test");
        new EmailDeliveryService(properties, provider(), testEnv)
                .sendVerification("synthetic@example.invalid", "token-test");
        assertThat(output.getAll()).doesNotContain("token-test");

        var devEnv = new MockEnvironment();
        devEnv.setActiveProfiles("dev");
        assertThatThrownBy(() -> new EmailDeliveryService(properties, provider(), devEnv)
                .sendVerification("synthetic@example.invalid", "token-dev"))
                .isInstanceOf(IllegalStateException.class);
    }
}
