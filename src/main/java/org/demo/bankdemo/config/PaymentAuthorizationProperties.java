package org.demo.bankdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.payment-authorization")
public class PaymentAuthorizationProperties {

    private String shopCallbackUrl = "http://localhost:8081/payment/callback";
    private String bankBaseUrl = "http://localhost:8080";
    private long expiryMinutes = 10;

    public String getShopCallbackUrl() { return shopCallbackUrl; }
    public void setShopCallbackUrl(String shopCallbackUrl) { this.shopCallbackUrl = shopCallbackUrl; }
    public String getBankBaseUrl() { return bankBaseUrl; }
    public void setBankBaseUrl(String bankBaseUrl) { this.bankBaseUrl = bankBaseUrl; }
    public long getExpiryMinutes() { return expiryMinutes; }
    public void setExpiryMinutes(long expiryMinutes) { this.expiryMinutes = expiryMinutes; }
}
