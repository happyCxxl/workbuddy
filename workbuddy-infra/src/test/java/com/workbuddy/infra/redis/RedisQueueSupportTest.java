package com.workbuddy.infra.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 队列封装单测：入队/阻塞弹出参数正确传递。
 */
@ExtendWith(MockitoExtension.class)
class RedisQueueSupportTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ListOperations<String, String> listOperations;

    @Test
    void leftPushShouldPushValueToKey() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOperations);

        new RedisQueueSupport(stringRedisTemplate).leftPush("q", "1");

        verify(listOperations).leftPush("q", "1");
    }

    @Test
    void rightPopShouldPopWithTimeout() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOperations);

        new RedisQueueSupport(stringRedisTemplate).rightPop("q", Duration.ofSeconds(5));

        verify(listOperations).rightPop("q", Duration.ofSeconds(5));
    }
}
