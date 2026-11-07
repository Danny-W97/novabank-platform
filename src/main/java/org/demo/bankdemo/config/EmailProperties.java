package org.demo.bankdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.email")
public class EmailProperties {

    public enum Mode { LOG, SMTP, NOOP }

    private Mode mode = Mode.SMTP;
    private String from = "no-reply@novabank.local";
    private String baseUrl = "http://localhost:8080";
    private String mailpitUrl = "http://localhost:18026";

    public String getMode() {
        return mode.name();
    }

    public void setMode(String mode) {
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("app.email.mode wymaga LOG albo SMTP.");
        }
        try {
            this.mode = Mode.valueOf(mode.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("app.email.mode wymaga LOG albo SMTP.");
        }
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getMailpitUrl() {
        return mailpitUrl;
    }

    public void setMailpitUrl(String mailpitUrl) {
        this.mailpitUrl = mailpitUrl;
    }
}
