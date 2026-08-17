package org.demo.shopapp.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class CertificateExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(CertificateExpiryJob.class);
    private final CertificateMonitorProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock = Clock.systemUTC();
    private final Map<String, AtomicLong> expiryGauges = new ConcurrentHashMap<>();
    private final AtomicLong errorGauge = new AtomicLong();

    public CertificateExpiryJob(CertificateMonitorProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        Gauge.builder("novabank_certificate_monitor_error", errorGauge, AtomicLong::doubleValue)
                .description("Czy ostatnia kontrola certyfikatow zakonczyla sie bledem (1/0)")
                .tag("service", "shop")
                .register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkAfterStartup() { checkIfEnabled(); }

    @Scheduled(cron = "${app.certificate-monitor.schedule:0 0 3 * * *}")
    public void checkScheduled() { checkIfEnabled(); }

    private void checkIfEnabled() {
        if (!properties.isEnabled()) return;
        var errors = 0;
        errors += checkPem("vault-tls", properties.getVaultCertificatePath());
        errors += checkStore("vault-truststore", properties.getTruststorePath(), properties.getTruststorePassword(), properties.getTruststoreType());
        errors += checkStore("shop-keystore", properties.getKeystorePath(), properties.getKeystorePassword(), properties.getKeystoreType());
        errorGauge.set(errors == 0 ? 0 : 1);
        if (errors == 0) log.info("Kontrola certyfikatow zakonczona poprawnie.");
        else log.warn("Kontrola certyfikatow wykryla {} problem(y). Sprawdz metryki i konfiguracje TLS.", errors);
    }

    private int checkPem(String name, String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) return recordMissing(name, "Brak sciezki certyfikatu Vault.");
        try (InputStream input = Files.newInputStream(toPath(configuredPath))) {
            var certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
            recordExpiry(name, certificate.getNotAfter().toInstant());
            return 0;
        } catch (Exception exception) { return recordError(name, exception); }
    }

    private int checkStore(String name, String configuredPath, String password, String type) {
        if (configuredPath == null || configuredPath.isBlank()) return recordMissing(name, "Brak sciezki magazynu certyfikatow.");
        try (InputStream input = Files.newInputStream(toPath(configuredPath))) {
            var store = KeyStore.getInstance(type == null || type.isBlank() ? "PKCS12" : type);
            store.load(input, password == null ? new char[0] : password.toCharArray());
            Instant earliest = null;
            Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                var certificate = store.getCertificate(aliases.nextElement());
                if (certificate instanceof X509Certificate x509) {
                    var expiry = x509.getNotAfter().toInstant();
                    if (earliest == null || expiry.isBefore(earliest)) earliest = expiry;
                }
            }
            if (earliest == null) return recordMissing(name, "Magazyn nie zawiera certyfikatu X.509.");
            recordExpiry(name, earliest);
            return 0;
        } catch (Exception exception) { return recordError(name, exception); }
    }

    private void recordExpiry(String name, Instant expiry) {
        var days = Duration.between(Instant.now(clock), expiry).toDays();
        var gauge = expiryGauges.computeIfAbsent(name, key -> {
            var value = new AtomicLong();
            Gauge.builder("novabank_certificate_expiry_days", value, AtomicLong::doubleValue)
                    .description("Liczba dni do wygasniecia certyfikatu")
                    .tag("service", "shop")
                    .tag("certificate", key)
                    .register(meterRegistry);
            return value;
        });
        gauge.set(days);
        log.info("Certyfikat {} wygasa za {} dni.", name, days);
    }

    private int recordMissing(String name, String message) {
        log.warn("Certyfikat {}: {}", name, message);
        recordExpiry(name, Instant.EPOCH);
        return 1;
    }

    private int recordError(String name, Exception exception) {
        log.warn("Nie mozna sprawdzic certyfikatu {}: {}", name, exception.getMessage());
        recordExpiry(name, Instant.EPOCH);
        return 1;
    }

    private static Path toPath(String configuredPath) {
        var value = configuredPath.strip();
        return Path.of(value.startsWith("file:") ? value.substring("file:".length()) : value);
    }
}
