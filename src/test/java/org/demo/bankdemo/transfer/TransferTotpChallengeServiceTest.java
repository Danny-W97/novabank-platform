package org.demo.bankdemo.transfer;

import org.demo.bankdemo.profile.TwoFactorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpSession;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TransferTotpChallengeServiceTest {
    private static final String EMAIL = "client@example.pl";
    private final MockHttpSession session = new MockHttpSession();
    private final Map<String, Map<String, String>> storage = new HashMap<>();
    private TransferTotpChallengeService service;
    private TwoFactorService twoFactor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var redis = mock(StringRedisTemplate.class);
        HashOperations<String, String, String> hashes = mock(HashOperations.class);
        when(redis.<String, String>opsForHash()).thenReturn(hashes);
        doAnswer(invocation -> {
            storage.put(invocation.getArgument(0), new HashMap<>(invocation.<Map<String, String>>getArgument(1)));
            return null;
        }).when(hashes).putAll(anyString(), anyMap());
        when(hashes.entries(anyString())).thenAnswer(invocation ->
                new HashMap<>(storage.getOrDefault(invocation.getArgument(0), Map.of())));
        when(hashes.increment(anyString(), eq("attempts"), anyLong())).thenAnswer(invocation -> {
            var values = storage.get(invocation.<String>getArgument(0));
            long count = Long.parseLong(values.get("attempts")) + 1;
            values.put("attempts", Long.toString(count));
            return count;
        });
        when(redis.delete(anyString())).thenAnswer(invocation -> storage.remove(invocation.getArgument(0)) != null);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    var arguments = (Object[]) invocation.getRawArguments()[2];
                    var keys = invocation.<java.util.List<String>>getArgument(1);
                    if (arguments.length > 0) {
                        var values = new HashMap<String,String>();
                        for (int i=1; i<arguments.length; i+=2)
                            values.put((String)arguments[i], (String)arguments[i+1]);
                        storage.put(keys.getFirst(), values);
                        return 1L;
                    }
                    var values = storage.get(keys.getFirst());
                    if (values == null) return -1L;
                    long count = Long.parseLong(values.get("attempts")) + 1;
                    if (count > 5) { storage.remove(keys.getFirst()); return -2L; }
                    values.put("attempts", Long.toString(count));
                    return count;
                });
        twoFactor = mock(TwoFactorService.class);
        service = new TransferTotpChallengeService(redis, twoFactor);
    }

    @Test
    void twoTabsCanConfirmTheirOwnChallengesIndependently() {
        var a = command("operation-A", "10.00");
        var b = command("operation-B", "20.00");
        service.start(session, EMAIL, a, "A");
        service.start(session, EMAIL, b, "B");
        when(twoFactor.verifyTotpCode(EMAIL, "valid")).thenReturn(true);
        assertThat(service.verify(session, EMAIL, a, "valid"))
                .isEqualTo(TransferTotpChallengeService.VerificationResult.VERIFIED);
        assertThat(service.verify(session, EMAIL, b, "valid"))
                .isEqualTo(TransferTotpChallengeService.VerificationResult.VERIFIED);
        assertThat(storage).isEmpty();
    }

    @Test
    void mismatchOfOneOperationCannotRemoveOtherTabsChallenge() {
        var a = command("operation-A", "10.00");
        var b = command("operation-B", "20.00");
        service.start(session, EMAIL, a, "A");
        service.start(session, EMAIL, b, "B");
        assertThat(service.verify(session, EMAIL, command("operation-A", "30.00"), "valid"))
                .isEqualTo(TransferTotpChallengeService.VerificationResult.MISMATCH);
        when(twoFactor.verifyTotpCode(EMAIL, "valid")).thenReturn(true);
        assertThat(service.verify(session, EMAIL, b, "valid"))
                .isEqualTo(TransferTotpChallengeService.VerificationResult.VERIFIED);
    }

    @Test
    void retryingSameOperationDoesNotResetAttemptCounter() {
        var a = command("operation-A", "10.00");
        service.start(session, EMAIL, a, "A");
        for (int i = 1; i <= 5; i++) {
            service.start(session, EMAIL, a, "A");
            assertThat(service.verify(session, EMAIL, a, "invalid")).isEqualTo(i == 5
                    ? TransferTotpChallengeService.VerificationResult.TOO_MANY_ATTEMPTS
                    : TransferTotpChallengeService.VerificationResult.INVALID_CODE);
        }
        assertThat(service.verify(session, EMAIL, a, "valid"))
                .isEqualTo(TransferTotpChallengeService.VerificationResult.EXPIRED);
    }

    private static TransferCommand command(String key, String amount) {
        return new TransferCommand(key, "Synthetic recipient", "12127943138124663147042900",
                new BigDecimal(amount), "Test");
    }

    @Test
    void expiredRedisChallengesAreRemovedFromSessionWhenStartingAnotherOperation() {
        service.start(session, EMAIL, command("operation-A", "10.00"), "A");
        storage.clear();
        service.start(session, EMAIL, command("operation-B", "20.00"), "B");
        assertThat(session.getAttribute(TransferTotpChallengeService.SESSION_ATTRIBUTE_PREFIX + "operation-A"))
                .isNull();
        assertThat(session.getAttribute(TransferTotpChallengeService.SESSION_ATTRIBUTE_PREFIX + "operation-B"))
                .isNotNull();
    }
}
