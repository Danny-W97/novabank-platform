package org.demo.bankdemo.retention;

import java.time.Instant;

public record RetentionRunView(
        String runId,
        Instant startedAt,
        Instant finishedAt,
        String status,
        boolean dryRun,
        int deletedCount,
        int anonymizedCount,
        int errorCount,
        String details
) {
}
