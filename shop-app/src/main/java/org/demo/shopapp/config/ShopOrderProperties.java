package org.demo.shopapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.orders")
public class ShopOrderProperties {

    private String merchantAccountNumber = "";
    private String merchantId = "";

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String value) { merchantId = value; }

    public String requireMerchantId() {
        if (merchantId == null || !merchantId.matches("[A-Za-z0-9_-]{1,64}"))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Shop merchant identity is not configured.");
        return merchantId;
    }
    private boolean reconciliationEnabled = true;
    private long reconciliationIntervalMs = 30000;
    private long reconciliationInitialDelayMs = 10000;
    private int reconciliationBatchSize = 50;

    public String getMerchantAccountNumber() {
        return merchantAccountNumber;
    }

    public void setMerchantAccountNumber(String merchantAccountNumber) {
        this.merchantAccountNumber = merchantAccountNumber;
    }

    public boolean isReconciliationEnabled() {
        return reconciliationEnabled;
    }

    public void setReconciliationEnabled(boolean reconciliationEnabled) {
        this.reconciliationEnabled = reconciliationEnabled;
    }

    public long getReconciliationIntervalMs() {
        return reconciliationIntervalMs;
    }

    public void setReconciliationIntervalMs(long reconciliationIntervalMs) {
        this.reconciliationIntervalMs = reconciliationIntervalMs;
    }

    public long getReconciliationInitialDelayMs() {
        return reconciliationInitialDelayMs;
    }

    public void setReconciliationInitialDelayMs(long reconciliationInitialDelayMs) {
        this.reconciliationInitialDelayMs = reconciliationInitialDelayMs;
    }

    public int getReconciliationBatchSize() {
        return reconciliationBatchSize;
    }

    public void setReconciliationBatchSize(int reconciliationBatchSize) {
        this.reconciliationBatchSize = reconciliationBatchSize;
    }
}
