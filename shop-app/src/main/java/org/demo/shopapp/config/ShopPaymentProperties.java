package org.demo.shopapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.payment-provider")
public class ShopPaymentProperties {

    private String provider = "bank-demo";
    private String apiBaseUrl = "https://api.stripe.com";
    private String apiKey = "";
    private String webhookSecret = "";
    private String successUrl = "http://localhost:8081/payment/stripe/success?order={ORDER_NUMBER}&session_id={CHECKOUT_SESSION_ID}";
    private String cancelUrl = "http://localhost:8081/payment/stripe/cancel?order={ORDER_NUMBER}";

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getApiBaseUrl() { return apiBaseUrl; }
    public void setApiBaseUrl(String apiBaseUrl) { this.apiBaseUrl = apiBaseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
    public String getSuccessUrl() { return successUrl; }
    public void setSuccessUrl(String successUrl) { this.successUrl = successUrl; }
    public String getCancelUrl() { return cancelUrl; }
    public void setCancelUrl(String cancelUrl) { this.cancelUrl = cancelUrl; }

    public boolean isStripe() {
        return "stripe".equalsIgnoreCase(provider);
    }
}
