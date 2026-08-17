package org.demo.shopapp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class ShopDistributedRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(ShopDistributedRateLimitService.class);
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    public ShopDistributedRateLimitService(
            StringRedisTemplate redisTemplate,
            ObjectProvider<MeterRegistry> meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    public Decision tryAcquire(String scope, String subject, int limit, Duration window) {
        var windowSeconds = Math.max(1, window.toSeconds());
        if (limit < 1) {
            return result(scope, false, windowSeconds, true);
        }
        var now = Instant.now();
        var windowNumber = now.getEpochSecond() / windowSeconds;
        var key = "shop:rate-limit:" + scope + ":" + sha256(subject) + ":" + windowNumber;
        try {
            var count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, Duration.ofSeconds(windowSeconds + 5));
            }
            var remaining = windowSeconds - (now.getEpochSecond() % windowSeconds);
            return result(scope, count != null && count <= limit, Math.max(1, remaining), true);
        } catch (DataAccessException exception) {
            log.error("Rate limiting Redis is unavailable for shop scope {}.", scope, exception);
            return result(scope, false, 30, false);
        }
    }

    private Decision result(String scope, boolean allowed, long retryAfterSeconds, boolean backendAvailable) {
        if (meterRegistry != null) {
            meterRegistry.counter(
                    "shop.rate_limit.requests",
                    "scope", scope,
                    "result", backendAvailable ? allowed ? "allowed" : "rejected" : "backend_unavailable"
            ).increment();
        }
        return new Decision(allowed, retryAfterSeconds, backendAvailable);
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "unknown" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Nie można utworzyć klucza limitu sklepu.", exception);
        }
    }

    public record Decision(boolean allowed, long retryAfterSeconds, boolean backendAvailable) {
    }
}
