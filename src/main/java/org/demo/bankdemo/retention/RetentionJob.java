package org.demo.bankdemo.retention;

import org.demo.bankdemo.config.RetentionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.retention.enabled", havingValue = "true")
public class RetentionJob {

    private final RetentionExecutionService executionService;
    private final RetentionProperties properties;

    public RetentionJob(RetentionExecutionService executionService, RetentionProperties properties) {
        this.executionService = executionService;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.retention.schedule:0 0 3 * * *}")
    public void runScheduled() {
        executionService.execute(properties.isDryRun());
    }
}
