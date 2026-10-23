package org.demo.bankdemo.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginAttemptServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private LoginAttemptService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new LoginAttemptService(redisTemplate);
    }

    @Test
    void blocksNormalizedEmailAfterFiveFailures() {
        var key = LoginAttemptService.key("anna.nowak@example.pl");
        when(valueOperations.get(key)).thenReturn("5");

        assertThat(service.isBlocked("  ANNA.NOWAK@EXAMPLE.PL ")).isTrue();
    }

    @Test
    void givesFirstFailureA15MinuteTtl() {
        var key = LoginAttemptService.key("anna.nowak@example.pl");
        when(redisTemplate.execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.eq(java.util.List.of(key)),org.mockito.ArgumentMatchers.eq("900000"))).thenReturn(1L);

        service.recordFailure("Anna.Nowak@Example.pl");

        verify(redisTemplate).execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.eq(java.util.List.of(key)),org.mockito.ArgumentMatchers.eq("900000"));
        org.mockito.Mockito.verify(redisTemplate,org.mockito.Mockito.never()).expire(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class));
    }

    @Test
    void clearsCounterAfterSuccessfulLogin() {
        var key = LoginAttemptService.key("anna.nowak@example.pl");

        service.clear(" ANNA.NOWAK@EXAMPLE.PL ");

        verify(redisTemplate).delete(key);
    }

    @Test
    void removesCorruptedCounterInsteadOfBlockingUser() {
        var key = LoginAttemptService.key("anna.nowak@example.pl");
        when(valueOperations.get(key)).thenReturn("not-a-number");

        assertThat(service.isBlocked("anna.nowak@example.pl")).isFalse();
        verify(redisTemplate).delete(key);
    }
}
