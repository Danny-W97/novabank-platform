package org.demo.bankdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.retention")
public class RetentionProperties {

    private boolean enabled;
    private boolean dryRun = true;
    private String schedule = "0 0 3 * * *";
    private int batchSize = 500;
    private int gracePeriodDays = 7;
    private int loginHistoryDays = 30;
    private int notificationDays = 30;
    private int operationAuditDays = 90;
    private int adminAuditDays = 365;
    private int retentionRunDays = 365;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public String getSchedule() { return schedule; }
    public void setSchedule(String schedule) { this.schedule = schedule; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getGracePeriodDays() { return gracePeriodDays; }
    public void setGracePeriodDays(int gracePeriodDays) { this.gracePeriodDays = gracePeriodDays; }
    public int getLoginHistoryDays() { return loginHistoryDays; }
    public void setLoginHistoryDays(int loginHistoryDays) { this.loginHistoryDays = loginHistoryDays; }
    public int getNotificationDays() { return notificationDays; }
    public void setNotificationDays(int notificationDays) { this.notificationDays = notificationDays; }
    public int getOperationAuditDays() { return operationAuditDays; }
    public void setOperationAuditDays(int operationAuditDays) { this.operationAuditDays = operationAuditDays; }
    public int getAdminAuditDays() { return adminAuditDays; }
    public void setAdminAuditDays(int adminAuditDays) { this.adminAuditDays = adminAuditDays; }
    public int getRetentionRunDays() { return retentionRunDays; }
    public void setRetentionRunDays(int retentionRunDays) { this.retentionRunDays = retentionRunDays; }
}
