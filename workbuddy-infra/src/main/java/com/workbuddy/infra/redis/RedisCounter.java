package com.workbuddy.infra.redis;

import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.common.utils.NullUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis 计数器：自增并返回新值，key 首次创建时写入过期时间。
 */
@Component
public class RedisCounter {

    /** 自增与设置 TTL 必须原子：分两步时进程中断会留下永不过期的 key */
    private static final RedisScript<Long> INCR_WITH_TTL_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('incr', KEYS[1]) "
                    + "if current == 1 then redis.call('pexpire', KEYS[1], ARGV[1]) end "
                    + "return current",
            Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    public RedisCounter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 计数 +1 并返回新值；仅首次创建时写入过期时间，后续自增不续期；脚本未返回值时按 0 处理。 */
    public long increment(String key, Duration ttl) {
        Long count = stringRedisTemplate.execute(INCR_WITH_TTL_SCRIPT, List.of(key),
                String.valueOf(ttl.toMillis()));
        return ObjectUtil.defaultIfNull(count, 0L);
    }

    /** 当前计数值；key 不存在或值不是数字时返回 0。 */
    public long get(String key) {
        String value = stringRedisTemplate.opsForValue().get(key);
        if (NullUtil.isNull(value)) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 清零（删除 key）。 */
    public void reset(String key) {
        stringRedisTemplate.delete(key);
    }
}
