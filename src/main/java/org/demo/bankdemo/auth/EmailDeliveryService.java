package org.demo.bankdemo.auth;

import org.demo.bankdemo.config.EmailProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class EmailDeliveryService {

    private final EmailProperties properties;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    public EmailDeliveryService(
            EmailProperties properties,
            ObjectProvider<JavaMailSender> mailSenderProvider
    ) {
        this.properties = properties;
        this.mailSenderProvider = mailSenderProvider;
    }

    public void sendVerification(String email, String token) {
        var link = link("/verify-email", token);
        deliver(email, "Potwierdź adres e-mail NovaBank",
                "Aby potwierdzić adres e-mail, otwórz link:\n\n" + link
                        + "\n\nLink jest jednorazowy i ważny 24 godziny.");
    }

    public void sendPasswordReset(String email, String token) {
        var link = link("/password/reset", token);
        deliver(email, "Reset hasła NovaBank",
                "Aby ustawić nowe hasło, otwórz link:\n\n" + link
                        + "\n\nLink jest jednorazowy i ważny 30 minut.");
    }

    private String link(String path, String token) {
        var baseUrl = properties.getBaseUrl().replaceAll("/+$", "");
        return baseUrl + path + "?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private void deliver(String email, String subject, String body) {
        if (!"smtp".equalsIgnoreCase(properties.getMode())) {
            System.out.printf("[EMAIL-DEV] to=%s subject=%s%n%s%n", email, subject, body);
            return;
        }

        var sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("Tryb SMTP wymaga konfiguracji spring.mail.");
        }
        var message = new SimpleMailMessage();
        message.setFrom(properties.getFrom());
        message.setTo(email);
        message.setSubject(subject);
        message.setText(body);
        sender.send(message);
    }
}
