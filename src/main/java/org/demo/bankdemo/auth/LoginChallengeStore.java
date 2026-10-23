package org.demo.bankdemo.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Authoritative shared state; old HttpSession snapshots cannot resurrect a consumed challenge. */
@Service
public class LoginChallengeStore {
    private static final String CHECK = """
            local expires=redis.call('HGET',KEYS[1],'expires')
            if not expires then return {} end
            local failures=tonumber(redis.call('HGET',KEYS[1],'failures') or '0')
            if tonumber(expires)<=tonumber(ARGV[1]) or failures>=5 then
              redis.call('DEL',KEYS[1]); return {} end
            """;
    private static final String VALUES = """
            local result={redis.call('HGET',KEYS[1],'email'),redis.call('HGET',KEYS[1],'target'),tostring(failures),expires}
            """;
    private final StringRedisTemplate redis;
    public LoginChallengeStore(StringRedisTemplate redis) { this.redis=redis; }

    public void start(String id,String email,String target,Instant expires) {
        redis.execute(new DefaultRedisScript<>("""
                redis.call('HSET',KEYS[1],'email',ARGV[1],'target',ARGV[2],'expires',ARGV[3],'failures','0')
                redis.call('PEXPIRE',KEYS[1],ARGV[4]); return 1
                """,Long.class),List.of(key(id)),email,target,Long.toString(expires.toEpochMilli()),"300000");
    }
    public Optional<TwoFactorLoginService.PendingLogin> read(String id,Instant now) { return get(id,now,false); }
    public Optional<TwoFactorLoginService.PendingLogin> take(String id,Instant now) { return get(id,now,true); }
    @SuppressWarnings("unchecked")
    private Optional<TwoFactorLoginService.PendingLogin> get(String id,Instant now,boolean consume) {
        if(id==null) return Optional.empty();
        List<String> values=redis.execute(new DefaultRedisScript<>(CHECK+VALUES+
                (consume?"redis.call('DEL',KEYS[1]); ":"")+"return result",List.class),List.of(key(id)),Long.toString(now.toEpochMilli()));
        if(values==null || values.size()!=4) return Optional.empty();
        return Optional.of(new TwoFactorLoginService.PendingLogin(id,values.get(0),values.get(1),Integer.parseInt(values.get(2)),
                Instant.ofEpochMilli(Long.parseLong(values.get(3)))));
    }
    public boolean failure(String id,Instant now) {
        if(id==null) return false;
        // Return an empty list for a missing/expired challenge, consistent with CHECK.
        @SuppressWarnings("unchecked")
        List<String> result=redis.execute(new DefaultRedisScript<>(CHECK+"""
                failures=redis.call('HINCRBY',KEYS[1],'failures',1)
                if failures>=5 then redis.call('DEL',KEYS[1]); return {} end
                return {tostring(failures)}
                """,List.class),List.of(key(id)),Long.toString(now.toEpochMilli()));
        return result!=null && !result.isEmpty();
    }
    public void delete(String id) { if(id!=null) redis.delete(key(id)); }
    private static String key(String id) {
        if(!id.matches("[A-Za-z0-9-]{1,64}")) throw new IllegalArgumentException("Invalid challenge ID.");
        return "bank:2fa-challenge:"+id;
    }
}
