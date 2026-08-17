package org.demo.shopapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.certificate-monitor")
public class CertificateMonitorProperties {

    private boolean enabled;
    private String schedule = "0 0 3 * * *";
    private String vaultCertificatePath = "";
    private String truststorePath = "";
    private String truststorePassword = "";
    private String truststoreType = "PKCS12";
    private String keystorePath = "";
    private String keystorePassword = "";
    private String keystoreType = "PKCS12";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSchedule() { return schedule; }
    public void setSchedule(String value) { this.schedule = value; }
    public String getVaultCertificatePath() { return vaultCertificatePath; }
    public void setVaultCertificatePath(String value) { this.vaultCertificatePath = value; }
    public String getTruststorePath() { return truststorePath; }
    public void setTruststorePath(String value) { this.truststorePath = value; }
    public String getTruststorePassword() { return truststorePassword; }
    public void setTruststorePassword(String value) { this.truststorePassword = value; }
    public String getTruststoreType() { return truststoreType; }
    public void setTruststoreType(String value) { this.truststoreType = value; }
    public String getKeystorePath() { return keystorePath; }
    public void setKeystorePath(String value) { this.keystorePath = value; }
    public String getKeystorePassword() { return keystorePassword; }
    public void setKeystorePassword(String value) { this.keystorePassword = value; }
    public String getKeystoreType() { return keystoreType; }
    public void setKeystoreType(String value) { this.keystoreType = value; }
}
