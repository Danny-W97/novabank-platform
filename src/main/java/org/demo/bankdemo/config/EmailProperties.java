package org.demo.bankdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.email")
public class EmailProperties {

    public enum Mode { LOG, SMTP }

    private Mode mode = Mode.LOG;
    private String from = "no-reply@novabank.local";
    private String baseUrl = "http://localhost:8080";

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
}
