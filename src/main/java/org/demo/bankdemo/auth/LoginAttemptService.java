package org.demo.bankdemo.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class LoginAttemptService {

    static final int MAX_FAILURES = 5;
    static final Duration BLOCK_DURATION = Duration.ofMinutes(15);
    private static final String KEY_PREFIX = "bank:login-attempt:";

    private final StringRedisTemplate redisTemplate;

    public LoginAttemptService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean isBlocked(String email) {
        var attempts = redisTemplate.opsForValue().get(key(email));
        if (attempts == null) {
            return false;
        }
        try {
            return Integer.parseInt(attempts) >= MAX_FAILURES;
        } catch (NumberFormatException exception) {
            redisTemplate.delete(key(email));
            return false;
        }
    }

    public void recordFailure(String email) {
        recordFailureCount(email);
    }

    public int recordFailureCount(String email) {
        if (email == null || email.isBlank()) {
            return 0;
        }
        var key = key(email);
        var attempts = org.demo.bankdemo.config.AtomicRedisCounter.increment(redisTemplate,key,BLOCK_DURATION);
        return (int)Math.min(attempts,Integer.MAX_VALUE);
    }

    public void clear(String email) {
        if (email != null && !email.isBlank()) {
            redisTemplate.delete(key(email));
        }
    }

    static String key(String email) {
        var normalized = email.strip().toLowerCase(Locale.ROOT);
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 nie jest dostępny.", exception);
        }
    }
}
