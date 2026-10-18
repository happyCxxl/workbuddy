package com.workbuddy.infra.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 队列：List 的左入队与右阻塞弹出。
 */
@Component
public class RedisQueueSupport {

    private final StringRedisTemplate stringRedisTemplate;

    public RedisQueueSupport(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 左侧入队 */
    public void leftPush(String key, String value) {
        stringRedisTemplate.opsForList().leftPush(key, value);
    }

    /** 右侧阻塞弹出（超时返回 null） */
    public String rightPop(String key, Duration timeout) {
        return stringRedisTemplate.opsForList().rightPop(key, timeout);
    }
}
