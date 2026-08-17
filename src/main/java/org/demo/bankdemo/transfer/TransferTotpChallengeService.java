package org.demo.bankdemo.transfer;

import jakarta.servlet.http.HttpSession;
import org.demo.bankdemo.profile.TwoFactorService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class TransferTotpChallengeService {

    static final String SESSION_ATTRIBUTE = "BANK_TRANSFER_TOTP_CHALLENGE";
    static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 5;
    private static final String KEY_PREFIX = "bank:transfer-totp:";

    private final StringRedisTemplate redisTemplate;
    private final TwoFactorService twoFactorService;

    public TransferTotpChallengeService(
            StringRedisTemplate redisTemplate,
            TwoFactorService twoFactorService
    ) {
        this.redisTemplate = redisTemplate;
        this.twoFactorService = twoFactorService;
    }

    public void start(HttpSession session, String email, TransferCommand command, String reason) {
        var existingId = (String) session.getAttribute(SESSION_ATTRIBUTE);
        if (existingId != null && matches(existingId, email, command)) {
            return;
        }
        var id = UUID.randomUUID().toString().replace("-", "");
        var values = new HashMap<String, String>();
        values.put("email", email.strip().toLowerCase());
        values.put("amount", command.amount().toPlainString());
        values.put("currency", "PLN");
        values.put("recipient", command.recipientName());
        values.put("account", command.destinationAccountNumber());
        values.put("title", command.title());
        values.put("idempotencyKey", command.idempotencyKey());
        values.put("reason", reason == null ? "" : reason);
        values.put("attempts", "0");
        redisTemplate.opsForHash().putAll(key(id), values);
        redisTemplate.expire(key(id), CHALLENGE_LIFETIME);
        session.setAttribute(SESSION_ATTRIBUTE, id);
    }

    public VerificationResult verify(
            HttpSession session,
            String email,
            TransferCommand command,
            String code
    ) {
        var id = (String) session.getAttribute(SESSION_ATTRIBUTE);
        if (id == null) return VerificationResult.EXPIRED;
        var values = redisTemplate.<String, String>opsForHash().entries(key(id));
        if (values.isEmpty()) {
            clear(session, id);
            return VerificationResult.EXPIRED;
        }
        if (!matches(values, email, command)) {
            clear(session, id);
            return VerificationResult.MISMATCH;
        }
        var attempts = redisTemplate.opsForHash().increment(key(id), "attempts", 1);
        if (attempts != null && attempts > MAX_ATTEMPTS) {
            clear(session, id);
            return VerificationResult.TOO_MANY_ATTEMPTS;
        }
        if (!twoFactorService.verifyTotpCode(email, code)) {
            if (attempts != null && attempts >= MAX_ATTEMPTS) {
                clear(session, id);
                return VerificationResult.TOO_MANY_ATTEMPTS;
            }
            return VerificationResult.INVALID_CODE;
        }
        clear(session, id);
        return VerificationResult.VERIFIED;
    }

    public String reason(HttpSession session) {
        var id = (String) session.getAttribute(SESSION_ATTRIBUTE);
        if (id == null) return "";
        var reason = redisTemplate.<String, String>opsForHash().get(key(id), "reason");
        return reason == null ? "" : reason;
    }

    private boolean matches(String id, String email, TransferCommand command) {
        return matches(redisTemplate.<String, String>opsForHash().entries(key(id)), email, command);
    }

    private static boolean matches(Map<String, String> values, String email, TransferCommand command) {
        return !values.isEmpty()
                && email.strip().equalsIgnoreCase(values.get("email"))
                && command.amount().toPlainString().equals(values.get("amount"))
                && command.recipientName().equals(values.get("recipient"))
                && command.destinationAccountNumber().equals(values.get("account"))
                && command.title().equals(values.get("title"))
                && command.idempotencyKey().equals(values.get("idempotencyKey"));
    }

    private void clear(HttpSession session, String id) {
        redisTemplate.delete(key(id));
        session.removeAttribute(SESSION_ATTRIBUTE);
    }

    private static String key(String id) {
        return KEY_PREFIX + id;
    }

    public enum VerificationResult {
        VERIFIED,
        INVALID_CODE,
        EXPIRED,
        MISMATCH,
        TOO_MANY_ATTEMPTS
    }
}
