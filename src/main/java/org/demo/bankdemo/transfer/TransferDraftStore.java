package org.demo.bankdemo.transfer;

import jakarta.servlet.http.HttpSession;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Component
public class TransferDraftStore {
    private final StringRedisTemplate redis;
    public TransferDraftStore(StringRedisTemplate redis) { this.redis = redis; }

    public void save(HttpSession session, TransferCommand command) {
        var prefix = prefix(session);
        redis.execute(new DefaultRedisScript<>("""
                redis.call('HSET',KEYS[1],'draft:'..ARGV[1],ARGV[2])
                local sequence=redis.call('HINCRBY',KEYS[1],'sequence',1)
                redis.call('ZADD',KEYS[2],sequence,ARGV[1])
                local count=redis.call('ZCARD',KEYS[2])
                if count>10 then
                  local expired=redis.call('ZRANGE',KEYS[2],0,count-11)
                  for _,id in ipairs(expired) do
                    redis.call('HDEL',KEYS[1],'draft:'..id); redis.call('ZREM',KEYS[2],id)
                  end
                end
                redis.call('EXPIRE',KEYS[1],ARGV[3]); redis.call('EXPIRE',KEYS[2],ARGV[3])
                return 1
                """, Long.class), List.of(prefix + ":data", prefix + ":order"),
                command.idempotencyKey(), encode(command), Integer.toString(
                        session.getMaxInactiveInterval() > 0 ? session.getMaxInactiveInterval() : 1800));
    }

    public Optional<TransferCommand> find(HttpSession session, String idempotencyKey) {
        if (session == null || idempotencyKey == null || idempotencyKey.isBlank()) return Optional.empty();
        var value = (String) redis.opsForHash().get(prefix(session) + ":data", "draft:" + idempotencyKey);
        if (value == null) return Optional.empty();
        var command = decode(value);
        if (!idempotencyKey.equals(command.idempotencyKey())) throw new IllegalStateException("Draft identity mismatch.");
        return Optional.of(command);
    }

    static String encode(TransferCommand command) {
        return java.util.stream.Stream.of(command.idempotencyKey(), command.recipientName(),
                command.destinationAccountNumber(), command.amount().toPlainString(), command.title())
                .map(value -> Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .collect(java.util.stream.Collectors.joining("."));
    }

    static TransferCommand decode(String value) {
        var parts = value.split("\\.", -1);
        if (parts.length != 5) throw new IllegalStateException("Invalid stored draft.");
        for (int i = 0; i < parts.length; i++)
            parts[i] = new String(Base64.getUrlDecoder().decode(parts[i]), StandardCharsets.UTF_8);
        return new TransferCommand(parts[0], parts[1], parts[2], new BigDecimal(parts[3]), parts[4]);
    }

    private static String prefix(HttpSession session) {
        try {
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(session.getId().getBytes(StandardCharsets.UTF_8)));
            return "bank:transfer-drafts:{" + hash + "}";
        } catch (Exception exception) { throw new IllegalStateException("Cannot identify draft session.", exception); }
    }
}
