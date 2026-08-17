package org.demo.shopapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.orders")
public class ShopOrderProperties {

    private String merchantAccountNumber = "";
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
