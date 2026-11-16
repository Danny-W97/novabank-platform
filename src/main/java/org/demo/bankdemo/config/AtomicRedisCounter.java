package org.demo.bankdemo.config;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.time.Duration;
import java.util.List;

public final class AtomicRedisCounter {
    private AtomicRedisCounter() { }
    public static long increment(StringRedisTemplate redis,String key,Duration ttl) {
        var count=redis.execute(new DefaultRedisScript<>("""
                local count=redis.call('INCR',KEYS[1])
                if redis.call('PTTL',KEYS[1])<0 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end
                return count
                """,Long.class),List.of(key),Long.toString(Math.max(1,ttl.toMillis())));
        if(count==null) throw new org.springframework.dao.DataAccessResourceFailureException("Redis counter returned no result.");
        return count;
    }
}
