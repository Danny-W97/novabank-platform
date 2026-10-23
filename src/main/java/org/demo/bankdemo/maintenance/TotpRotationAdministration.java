package org.demo.bankdemo.maintenance;

import org.demo.bankdemo.security.TotpKeyRotationService;
import org.demo.bankdemo.security.TotpSecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Explicit local administrative command. No HTTP endpoint or automatic startup execution. */
public final class TotpRotationAdministration {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TotpSecretCipher cipher;
    private final TotpKeyRotationService rotation;

    public TotpRotationAdministration(DataSource source, TotpSecretCipher cipher) {
        this.jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(10);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        transactions.setTimeout(15);
        this.cipher = cipher;
        this.rotation = new TotpKeyRotationService(jdbc, cipher);
    }

    public Plan plan(long afterId, int limit) {
        validate(afterId, limit);
        return transactions.execute(status -> {
            var digest = digest(afterId, limit);
            var result = rotation.rotateBatch(afterId, limit, true);
            return new Plan(digest, result.inspected(), result.requiringMigration(), result.nextCheckpoint(), cipher.activeKeyId(), false);
        });
    }

    public Plan apply(long afterId, int limit, String expectedDigest, String confirmation) {
        validate(afterId, limit);
        if (!"ROTATE".equals(confirmation) || expectedDigest == null || !expectedDigest.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Rotation requires the reviewed plan digest and ROTATE confirmation.");
        return transactions.execute(status -> {
            // Serializes administration calls and durable replay after a lost acknowledgement.
            jdbc.execute("SELECT pg_advisory_xact_lock(719034812)");
            var old = jdbc.query("SELECT after_id,batch_size,inspected,migrated,next_checkpoint,active_key_id,configuration_digest FROM totp_rotation_runs WHERE plan_digest=?",
                    (rs, n) -> {
                        if (rs.getLong(1) != afterId || rs.getInt(2) != limit
                                || !rs.getString(7).equals(cipher.configurationFingerprint()))
                            throw new IllegalArgumentException("Rotation plan belongs to a different range or key configuration.");
                        return new Plan(expectedDigest, rs.getInt(3), rs.getInt(4), rs.getLong(5), rs.getString(6), true);
                    }, expectedDigest);
            if (!old.isEmpty()) return old.getFirst();
            var current = digest(afterId, limit);
            if (!MessageDigest.isEqual(expectedDigest.getBytes(StandardCharsets.UTF_8), current.getBytes(StandardCharsets.UTF_8)))
                throw new IllegalStateException("Rotation source or key configuration changed. Generate a new plan; no data changed.");
            var result = rotation.rotateBatch(afterId, limit, false);
            jdbc.update("INSERT INTO totp_rotation_runs(plan_digest,after_id,batch_size,inspected,migrated,next_checkpoint,active_key_id,configuration_digest) VALUES (?,?,?,?,?,?,?,?)",
                    expectedDigest, afterId, limit, result.inspected(), result.requiringMigration(), result.nextCheckpoint(),
                    cipher.activeKeyId(), cipher.configurationFingerprint());
            return new Plan(expectedDigest, result.inspected(), result.requiringMigration(), result.nextCheckpoint(), cipher.activeKeyId(), false);
        });
    }

    private String digest(long afterId, int limit) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update((afterId+":"+limit+":"+cipher.configurationFingerprint()).getBytes(StandardCharsets.UTF_8));
            jdbc.query("SELECT id,totp_secret_encrypted FROM users WHERE id>? AND totp_secret_encrypted IS NOT NULL ORDER BY id LIMIT ? FOR UPDATE",
                    (org.springframework.jdbc.core.RowCallbackHandler) row -> digest.update(
                            ("\n"+row.getLong(1)+":"+row.getString(2)).getBytes(StandardCharsets.UTF_8)), afterId, limit);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static void validate(long afterId, int limit) {
        if (afterId < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid rotation range.");
    }
    public record Plan(String planDigest, int inspected, int requiringMigration, long nextCheckpoint,
                       String activeKeyId, boolean replayed) { }
}
