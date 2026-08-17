package org.demo.bankdemo.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class RetentionRunRepository {

    private final JdbcTemplate jdbcTemplate;

    public RetentionRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String start(Instant startedAt, boolean dryRun) {
        var runId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO retention_runs "
                        + "(run_id, started_at, status, dry_run) VALUES (?, ?, 'RUNNING', ?)",
                runId, Timestamp.from(startedAt), dryRun
        );
        return runId;
    }

    public void finish(
            String runId,
            Instant finishedAt,
            String status,
            int deletedCount,
            int anonymizedCount,
            int errorCount,
            String details
    ) {
        jdbcTemplate.update(
                "UPDATE retention_runs SET finished_at = ?, status = ?, deleted_count = ?, "
                        + "anonymized_count = ?, error_count = ?, details = ? WHERE run_id = ?",
                Timestamp.from(finishedAt), status, deletedCount, anonymizedCount, errorCount,
                truncate(details), runId
        );
    }

    public List<RetentionRunView> recent(int limit) {
        return jdbcTemplate.query(
                "SELECT run_id, started_at, finished_at, status, dry_run, deleted_count, "
                        + "anonymized_count, error_count, details FROM retention_runs "
                        + "ORDER BY started_at DESC LIMIT ?",
                (rs, rowNum) -> new RetentionRunView(
                        rs.getString("run_id"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                        rs.getString("status"),
                        rs.getBoolean("dry_run"),
                        rs.getInt("deleted_count"),
                        rs.getInt("anonymized_count"),
                        rs.getInt("error_count"),
                        rs.getString("details")
                ),
                Math.max(1, Math.min(limit, 100))
        );
    }

    public void deleteOldRuns(Instant cutoff) {
        jdbcTemplate.update("DELETE FROM retention_runs WHERE created_at < ?", Timestamp.from(cutoff));
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= 500) {
            return value;
        }
        return value.substring(0, 497) + "...";
    }
}
