package com.workbuddy.infra.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Redis 分布式锁：SET NX EX 抢占，Lua 比对 token 后释放。
 */
@Component
public class RedisLock {

    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    public RedisLock(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 尝试加锁：成功返回 token，失败返回 null */
    public String tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(ok) ? token : null;
    }

    /** 释放锁：比对 token 后删除（只删自己持有的锁） */
    public void unlock(String key, String token) {
        stringRedisTemplate.execute(RELEASE_SCRIPT, List.of(key), token);
    }
}
