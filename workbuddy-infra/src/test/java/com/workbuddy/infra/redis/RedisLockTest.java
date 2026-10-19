package com.workbuddy.infra.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 分布式锁单测：SET NX EX 抢占与 Lua 比对释放。
 */
@ExtendWith(MockitoExtension.class)
class RedisLockTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    /**
     * 守护 {@code tryLockShouldReturnTokenWhenAcquired}：该场景行为改动即失败。
     */
    @Test
    void tryLockShouldReturnTokenWhenAcquired() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("lock"), any(), eq(Duration.ofSeconds(30)))).thenReturn(true);

        String token = new RedisLock(stringRedisTemplate).tryLock("lock", Duration.ofSeconds(30));

        assertEquals(32, token.length());
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).setIfAbsent(eq("lock"), captor.capture(), eq(Duration.ofSeconds(30)));
        assertEquals(token, captor.getValue());
    }

    /**
     * 守护 {@code tryLockShouldReturnNullWhenNotAcquired}：该场景行为改动即失败。
     */
    @Test
    void tryLockShouldReturnNullWhenNotAcquired() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("lock"), any(), eq(Duration.ofSeconds(30)))).thenReturn(false);

        String token = new RedisLock(stringRedisTemplate).tryLock("lock", Duration.ofSeconds(30));

        assertNull(token);
    }

    /**
     * 守护 {@code unlockShouldExecuteReleaseScriptWithKeyAndToken}：该场景行为改动即失败。
     */
    @Test
    void unlockShouldExecuteReleaseScriptWithKeyAndToken() {
        RedisLock lock = new RedisLock(stringRedisTemplate);
        when(stringRedisTemplate.execute(ArgumentMatchers.<RedisScript<Long>>any(), any(), any())).thenReturn(1L);

        lock.unlock("lock", "token-1");

        verify(stringRedisTemplate).execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of("lock")), eq("token-1"));
        org.junit.jupiter.api.Assertions.assertTrue(org.mockito.Mockito.mockingDetails(stringRedisTemplate).getInvocations().stream()
                .anyMatch(inv -> "execute".equals(inv.getMethod().getName())));
    }
}
