package org.demo.bankdemo.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit batch primitive, not an automatic startup job or a public HTTP endpoint. */
@Service
public class TotpKeyRotationService {
    private final JdbcTemplate jdbc;
    private final TotpSecretCipher cipher;
    public TotpKeyRotationService(JdbcTemplate jdbc, TotpSecretCipher cipher) { this.jdbc=jdbc; this.cipher=cipher; }

    @Transactional
    public Result rotateBatch(long afterId,int limit,boolean dryRun) {
        if(afterId<0 || limit<1 || limit>100) throw new IllegalArgumentException("Invalid rotation page.");
        var rows=jdbc.query("""
                SELECT id,totp_secret_encrypted FROM users WHERE id>? AND totp_secret_encrypted IS NOT NULL
                ORDER BY id LIMIT ? FOR UPDATE
                """,(rs,n)->new Row(rs.getLong(1),rs.getString(2)),afterId,limit);
        long last=afterId; int migrated=0;
        for(var row:rows) {
            // Validate even current records. A malformed record aborts the whole page and its checkpoint.
            cipher.decrypt(row.encrypted());
            if(!cipher.isCurrent(row.encrypted())) {
                if(!dryRun && jdbc.update("UPDATE users SET totp_secret_encrypted=? WHERE id=? AND totp_secret_encrypted=?",
                        cipher.reencrypt(row.encrypted()),row.id(),row.encrypted())!=1)
                    throw new IllegalStateException("TOTP rotation conflict; batch rolled back.");
                migrated++;
            }
            last=row.id();
        }
        return new Result(rows.size(),migrated,last,dryRun);
    }
    private record Row(long id,String encrypted) { }
    public record Result(int inspected,int requiringMigration,long nextCheckpoint,boolean dryRun) { }
}
