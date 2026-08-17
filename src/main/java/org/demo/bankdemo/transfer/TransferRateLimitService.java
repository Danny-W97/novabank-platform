package org.demo.bankdemo.transfer;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@Service
public class TransferRateLimitService {

    private final StringRedisTemplate redisTemplate;

    public TransferRateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryAcquire(String email, int maxRequests) {
        if (maxRequests < 1) {
            return false;
        }
        var window = Instant.now().getEpochSecond() / 60;
        var key = "bank:transfer-rate:" + sha256(email) + ":" + window;
        var count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, Duration.ofMinutes(2));
        }
        return count != null && count <= maxRequests;
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            var result = new StringBuilder(64);
            for (var part : digest) {
                result.append(String.format("%02x", part));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Nie można utworzyć klucza limitu przelewów.", exception);
        }
    }
}
