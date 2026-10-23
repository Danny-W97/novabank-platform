package org.demo.bankdemo.transfer;

import jakarta.servlet.http.HttpSession;
import org.demo.bankdemo.profile.TwoFactorService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.Collections;

@Service
public class TransferTotpChallengeService {

    static final String SESSION_ATTRIBUTE_PREFIX = "BANK_TRANSFER_TOTP_CHALLENGE:";
    static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_ACTIVE_CHALLENGES = 10;
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
        var existingId = (String) session.getAttribute(attribute(command));
        if (existingId != null && matches(existingId, email, command)) {
            return;
        }
        var active = pruneExpiredChallenges(session);
        if (session.getAttribute(attribute(command)) == null && active >= MAX_ACTIVE_CHALLENGES) {
            throw new TransferException(null, "Zakończ wcześniejsze potwierdzenia przelewów lub poczekaj na ich wygaśnięcie.");
        }
        if (existingId != null) clear(session, existingId, command);
        var id = UUID.randomUUID().toString().replace("-", "");
        var values = new HashMap<String, String>();
        values.put("email", email.strip().toLowerCase(Locale.ROOT));
        values.put("amount", command.amount().toPlainString());
        values.put("currency", "PLN");
        values.put("recipient", command.recipientName());
        values.put("account", command.destinationAccountNumber());
        values.put("title", command.title());
        values.put("idempotencyKey", command.idempotencyKey());
        values.put("reason", reason == null ? "" : reason);
        values.put("attempts", "0");
        var args = new java.util.ArrayList<String>();
        args.add(Long.toString(CHALLENGE_LIFETIME.toMillis()));
        values.forEach((field,value) -> { args.add(field); args.add(value); });
        redisTemplate.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
                redis.call('HSET',KEYS[1],unpack(ARGV,2))
                redis.call('PEXPIRE',KEYS[1],ARGV[1]); return 1
                """,Long.class),java.util.List.of(key(id)),args.toArray());
        // Separate Spring Session attributes also avoid last-writer-wins updates to a shared map.
        session.setAttribute(attribute(command), id);
    }

    public VerificationResult verify(
            HttpSession session,
            String email,
            TransferCommand command,
            String code
    ) {
        var id = (String) session.getAttribute(attribute(command));
        if (id == null) return VerificationResult.EXPIRED;
        var values = redisTemplate.<String, String>opsForHash().entries(key(id));
        if (values.isEmpty()) {
            clear(session, id, command);
            return VerificationResult.EXPIRED;
        }
        if (!matches(values, email, command)) {
            clear(session, id, command);
            return VerificationResult.MISMATCH;
        }
        var attempts = redisTemplate.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>("""
                if redis.call('EXISTS',KEYS[1])==0 then return -1 end
                local count=redis.call('HINCRBY',KEYS[1],'attempts',1)
                if count>5 then redis.call('DEL',KEYS[1]); return -2 end
                return count
                """,Long.class),java.util.List.of(key(id)));
        if (attempts == null || attempts == -1) return VerificationResult.EXPIRED;
        if (attempts == -2) {
            clear(session, id, command);
            return VerificationResult.TOO_MANY_ATTEMPTS;
        }
        if (!twoFactorService.verifyTotpCode(email, code)) {
            if (attempts >= MAX_ATTEMPTS) {
                clear(session, id, command);
                return VerificationResult.TOO_MANY_ATTEMPTS;
            }
            return VerificationResult.INVALID_CODE;
        }
        if (!Boolean.TRUE.equals(redisTemplate.delete(key(id)))) return VerificationResult.EXPIRED;
        session.removeAttribute(attribute(command));
        return VerificationResult.VERIFIED;
    }

    public String reason(HttpSession session, TransferCommand command) {
        var id = (String) session.getAttribute(attribute(command));
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

    private void clear(HttpSession session, String id, TransferCommand command) {
        redisTemplate.delete(key(id));
        session.removeAttribute(attribute(command));
    }

    private int pruneExpiredChallenges(HttpSession session) {
        int active = 0;
        for (var name : Collections.list(session.getAttributeNames())) {
            if (!name.startsWith(SESSION_ATTRIBUTE_PREFIX)) continue;
            if (!(session.getAttribute(name) instanceof String id)
                    || redisTemplate.<String, String>opsForHash().entries(key(id)).isEmpty()) {
                session.removeAttribute(name);
            } else {
                active++;
            }
        }
        return active;
    }

    private static String attribute(TransferCommand command) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()
                || command.idempotencyKey().length() > 64) {
            throw new TransferException(null, "Niepoprawny klucz operacji.");
        }
        return SESSION_ATTRIBUTE_PREFIX + command.idempotencyKey();
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
