package org.demo.bankdemo.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE password_reset_tokens SET used_at = :now
            WHERE token_hash = :tokenHash AND used_at IS NULL AND expires_at > :now
            """, nativeQuery = true)
    int consumeUnused(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    void deleteAllByUserId(Long userId);
}
