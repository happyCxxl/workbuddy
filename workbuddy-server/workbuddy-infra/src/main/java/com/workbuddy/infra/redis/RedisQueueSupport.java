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

    /**
     * 构造队列操作。
     *
     * @param stringRedisTemplate Redis 客户端
     */
    public RedisQueueSupport(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 左侧入队。
     *
     * @param key   队列键
     * @param value 入队元素
     */
    public void leftPush(String key, String value) {
        stringRedisTemplate.opsForList().leftPush(key, value);
    }

    /**
     * 右侧阻塞弹出。
     *
     * @param key     队列键
     * @param timeout 阻塞上限
     * @return 队首元素；超时为 null
     */
    public String rightPop(String key, Duration timeout) {
        return stringRedisTemplate.opsForList().rightPop(key, timeout);
    }
}
