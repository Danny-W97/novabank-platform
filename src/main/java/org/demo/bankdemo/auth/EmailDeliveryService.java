package org.demo.bankdemo.auth;

import jakarta.mail.MessagingException;
import org.demo.bankdemo.config.EmailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class EmailDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(EmailDeliveryService.class);

    private final EmailProperties properties;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final Environment environment;

    public EmailDeliveryService(
            EmailProperties properties,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            Environment environment
    ) {
        this.properties = properties;
        this.mailSenderProvider = mailSenderProvider;
        this.environment = environment;
    }

    public void sendVerification(String email, String token) {
        var link = link("/verify-email", token);
        var plainBody = "Witaj w NovaBank!\n\n"
                + "Aby aktywować konto bankowe, kliknij poniższy link:\n"
                + "Aktywuj konto bankowe: " + link + "\n\n"
                + "Link jest jednorazowy i ważny przez 24 godziny.";
        var htmlBody = """
                <div style="font-family: Inter, Arial, sans-serif; color: #0f172a; line-height: 1.6; max-width: 560px;">
                  <h2 style="margin-bottom: 12px;">Potwierdzenie rejestracji w NovaBank</h2>
                  <p>Dziękujemy za założenie konta w NovaBank. Aby dokończyć rejestrację i odblokować logowanie, potwierdź swój adres e-mail:</p>
                  <p style="margin: 24px 0;">
                    <a href="%s" style="display: inline-block; background-color: #2563eb; color: #ffffff; text-decoration: none; font-weight: 600; padding: 12px 24px; border-radius: 8px;">Aktywuj konto bankowe</a>
                  </p>
                  <p style="font-size: 13px; color: #475569;">Link jest jednorazowy i ważny przez 24 godziny.</p>
                  <p style="font-size: 12px; color: #64748b; word-break: break-all;">Jeśli przycisk nie działa, skopiuj adres do przeglądarki:<br><a href="%s">%s</a></p>
                </div>
                """.formatted(link, link, link);
        deliver(email, "Potwierdź adres e-mail NovaBank", plainBody, htmlBody);
    }

    public void sendPasswordReset(String email, String token) {
        var link = link("/password/reset", token);
        var plainBody = "Aby ustawić nowe hasło w NovaBank, otwórz link:\n\n" + link
                + "\n\nLink jest jednorazowy i ważny 30 minut.";
        var htmlBody = """
                <div style="font-family: Inter, Arial, sans-serif; color: #0f172a; line-height: 1.6; max-width: 560px;">
                  <h2 style="margin-bottom: 12px;">Reset hasła w NovaBank</h2>
                  <p>Otrzymaliśmy prośbę o zmianę hasła do Twojego konta. Aby ustawić nowe hasło, kliknij poniższy link:</p>
                  <p style="margin: 24px 0;">
                    <a href="%s" style="display: inline-block; background-color: #2563eb; color: #ffffff; text-decoration: none; font-weight: 600; padding: 12px 24px; border-radius: 8px;">Ustaw nowe hasło</a>
                  </p>
                  <p style="font-size: 13px; color: #475569;">Link jest jednorazowy i ważny przez 30 minut.</p>
                  <p style="font-size: 12px; color: #64748b; word-break: break-all;">Jeśli przycisk nie działa, skopiuj adres do przeglądarki:<br><a href="%s">%s</a></p>
                </div>
                """.formatted(link, link, link);
        deliver(email, "Reset hasła NovaBank", plainBody, htmlBody);
    }

    private String link(String path, String token) {
        var baseUrl = properties.getBaseUrl().replaceAll("/+$", "");
        return baseUrl + path + "?token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private void deliver(String email, String subject, String plainBody, String htmlBody) {
        if ("LOG".equals(properties.getMode())) {
            throw new IllegalStateException(
                    "Logowanie treści email i tokenów zostało wyłączone. Użyj EMAIL_MODE=SMTP.");
        }
        if ("NOOP".equals(properties.getMode())) {
            if (!environment.acceptsProfiles(Profiles.of("test"))
                    || environment.acceptsProfiles(Profiles.of("dev", "prod"))) {
                throw new IllegalStateException(
                        "Tryb NOOP jest dozwolony wyłącznie w izolowanym profilu test.");
            }
            return;
        }

        var sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            throw new EmailDeliveryException("Tryb SMTP wymaga konfiguracji spring.mail.");
        }
        try {
            var mimeMessage = sender.createMimeMessage();
            if (mimeMessage != null) {
                var helper = new MimeMessageHelper(mimeMessage, true, StandardCharsets.UTF_8.name());
                helper.setFrom(properties.getFrom());
                helper.setTo(email);
                helper.setSubject(subject);
                helper.setText(plainBody, htmlBody);
                sender.send(mimeMessage);
            } else {
                var message = new SimpleMailMessage();
                message.setFrom(properties.getFrom());
                message.setTo(email);
                message.setSubject(subject);
                message.setText(plainBody);
                sender.send(message);
            }
        } catch (MailException | MessagingException exception) {
            log.error("Błąd dostarczania wiadomości e-mail przez SMTP (temat: {}): {}",
                    subject, exception.getMessage());
            throw new EmailDeliveryException(
                    "Nie udało się wysłać wiadomości e-mail przez serwer pocztowy SMTP.", exception);
        }
    }
}
