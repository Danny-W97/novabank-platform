package org.demo.bankdemo.retention;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.demo.bankdemo.config.RetentionProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Service
public class RetentionExecutionService {

    private static final long ADVISORY_LOCK_KEY = 7_319_204_111L;

    private final JdbcTemplate jdbcTemplate;
    private final RetentionProperties properties;
    private final RetentionRunRepository runRepository;
    private final Clock clock;
    private final Counter deletedCounter;
    private final Counter errorCounter;

    public RetentionExecutionService(
            JdbcTemplate jdbcTemplate,
            RetentionProperties properties,
            RetentionRunRepository runRepository,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.runRepository = runRepository;
        this.clock = clock;
        this.deletedCounter = Counter.builder("bank.retention.deleted")
                .description("Liczba usunietych rekordow danych technicznych")
                .register(meterRegistry);
        this.errorCounter = Counter.builder("bank.retention.errors")
                .description("Liczba bledow zadania retencji")
                .register(meterRegistry);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RetentionResult execute(boolean dryRun) {
        var now = Instant.now(clock);
        var runId = runRepository.start(now, dryRun);
        var lockAcquired = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_KEY));
        if (!lockAcquired) {
            runRepository.finish(runId, Instant.now(clock), "SKIPPED", 0, 0, 0,
                    "Inne zadanie retencji jest juz uruchomione.");
            return new RetentionResult(runId, "SKIPPED", dryRun, 0, 0, 0,
                    "Inne zadanie retencji jest juz uruchomione.");
        }

        var cutoff = now.minus(properties.getGracePeriodDays(), ChronoUnit.DAYS);
        var deleted = 0;
        var errors = 0;
        var details = new ArrayList<String>();
        try {
            deleted += purge("email_verification_tokens", "expires_at", cutoff, dryRun, details);
            deleted += purge("password_reset_tokens", "expires_at", cutoff, dryRun, details);
            deleted += purgeOlderThan("login_history", "created_at", properties.getLoginHistoryDays(), dryRun, details);
            deleted += purgeOlderThan("notifications", "created_at", properties.getNotificationDays(), dryRun, details);
            deleted += purgeOlderThan("operation_audits", "created_at", properties.getOperationAuditDays(), dryRun, details);
            deleted += purgeOlderThan("admin_status_changes", "created_at", properties.getAdminAuditDays(), dryRun, details);
            deleted += purgeOldRuns(dryRun, details);
        } catch (RuntimeException exception) {
            errors++;
            errorCounter.increment();
            details.add("Blad: " + exception.getClass().getSimpleName());
        }

        var status = errors == 0 ? "SUCCESS" : "FAILED";
        var finishedAt = Instant.now(clock);
        runRepository.finish(runId, finishedAt, status, deleted, 0, errors,
                String.join("; ", details));
        deletedCounter.increment(deleted);
        return new RetentionResult(runId, status, dryRun, deleted, 0, errors,
                String.join("; ", details));
    }

    public List<RetentionRunView> recentRuns() {
        return runRepository.recent(25);
    }

    private int purge(
            String table,
            String column,
            Instant cutoff,
            boolean dryRun,
            List<String> details
    ) {
        var count = count(table, column, cutoff);
        details.add(table + "=" + count);
        if (!dryRun && count > 0) {
            var deleted = 0;
            int batch;
            do {
                batch = jdbcTemplate.update(
                        "DELETE FROM " + table + " WHERE id IN (SELECT id FROM " + table
                                + " WHERE " + column + " < ? ORDER BY id LIMIT ?)",
                        Timestamp.from(cutoff), Math.max(1, properties.getBatchSize())
                );
                deleted += batch;
            } while (batch > 0);
            return deleted;
        }
        return count;
    }

    private int purgeOlderThan(
            String table,
            String column,
            int days,
            boolean dryRun,
            List<String> details
    ) {
        var cutoff = Instant.now(clock).minus(Math.max(1, days), ChronoUnit.DAYS);
        return purge(table, column, cutoff, dryRun, details);
    }

    private int purgeOldRuns(boolean dryRun, List<String> details) {
        var cutoff = Instant.now(clock).minus(Math.max(1, properties.getRetentionRunDays()), ChronoUnit.DAYS);
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM retention_runs WHERE created_at < ?", Integer.class, Timestamp.from(cutoff));
        details.add("retention_runs=" + count);
        if (!dryRun && count != null && count > 0) {
            runRepository.deleteOldRuns(cutoff);
            return count;
        }
        return count == null ? 0 : count;
    }

    private int count(String table, String column, Instant cutoff) {
        var value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + column + " < ?", Integer.class,
                Timestamp.from(cutoff));
        return value == null ? 0 : value;
    }

    public record RetentionResult(
            String runId,
            String status,
            boolean dryRun,
            int deletedCount,
            int anonymizedCount,
            int errorCount,
            String details
    ) {
    }
}
