package org.demo.bankdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "app.transfer")
public class TransferLimitsProperties {

    private BigDecimal singleLimit = new BigDecimal("50000.00");
    private BigDecimal dailyLimit = new BigDecimal("100000.00");
    private int rateLimitPerMinute = 10;
    private BigDecimal totpRequiredAbove = new BigDecimal("5000.00");

    public BigDecimal getSingleLimit() { return singleLimit; }
    public void setSingleLimit(BigDecimal singleLimit) { this.singleLimit = singleLimit; }
    public BigDecimal getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(BigDecimal dailyLimit) { this.dailyLimit = dailyLimit; }
    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(int rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }
    public BigDecimal getTotpRequiredAbove() { return totpRequiredAbove; }
    public void setTotpRequiredAbove(BigDecimal totpRequiredAbove) { this.totpRequiredAbove = totpRequiredAbove; }
}
