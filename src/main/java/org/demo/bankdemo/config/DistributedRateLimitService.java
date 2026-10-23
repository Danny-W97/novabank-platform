package org.demo.bankdemo.config;

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
public class DistributedRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(DistributedRateLimitService.class);
    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    public DistributedRateLimitService(
            StringRedisTemplate redisTemplate,
            ObjectProvider<MeterRegistry> meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    public Decision tryAcquire(String scope, String subject, int limit, Duration window) {
        if (limit < 1 || window.isZero() || window.isNegative()) {
            return result(scope, false, window.toSeconds(), true);
        }
        var now = Instant.now();
        var windowSeconds = Math.max(1, window.toSeconds());
        var windowNumber = now.getEpochSecond() / windowSeconds;
        var key = "bank:rate-limit:" + scope + ":" + sha256(subject) + ":" + windowNumber;
        try {
            var count = AtomicRedisCounter.increment(redisTemplate,key,Duration.ofSeconds(windowSeconds + 5));
            var remaining = windowSeconds - (now.getEpochSecond() % windowSeconds);
            return result(scope, count <= limit, Math.max(1, remaining), true);
        } catch (DataAccessException exception) {
            log.error("Rate limiting Redis is unavailable for scope {}.", scope, exception);
            return result(scope, false, 30, false);
        }
    }

    private Decision result(String scope, boolean allowed, long retryAfterSeconds, boolean backendAvailable) {
        if (meterRegistry != null) {
            meterRegistry.counter(
                    "bank.rate_limit.requests",
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
            throw new IllegalStateException("Nie można utworzyć klucza limitu.", exception);
        }
    }

    public record Decision(boolean allowed, long retryAfterSeconds, boolean backendAvailable) {
    }
}
