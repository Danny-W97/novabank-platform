package org.demo.bankdemo.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class TotpReplayGuard {
    private final JdbcTemplate jdbc;
    public TotpReplayGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public boolean consume(Long userId, String secret, long step) {
        try {
            // Stable through encryption-key rotation. The TOTP seed has high entropy; it is never stored here.
            var fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8)));
            return jdbc.update("""
                    INSERT INTO totp_consumption(user_id,token_fingerprint,last_step) VALUES (?,?,?)
                    ON CONFLICT (user_id,token_fingerprint) DO UPDATE
                    SET last_step=EXCLUDED.last_step,used_at=CURRENT_TIMESTAMP
                    WHERE totp_consumption.last_step < EXCLUDED.last_step
                    """, userId,fingerprint,step)==1;
        } catch(java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
