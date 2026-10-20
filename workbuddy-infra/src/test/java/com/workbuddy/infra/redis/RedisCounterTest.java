package com.workbuddy.infra.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 计数器单测。
 *
 * <p>守护 {@code increment}/{@code get}/{@code reset} 的口径：自增脚本未返回值按 0 处理、
 * 键缺失或值非数字按 0 处理、清零即删键。Redis 客户端为系统边界，用替身。
 */
@ExtendWith(MockitoExtension.class)
class RedisCounterTest {

    @Mock
    private StringRedisTemplate template;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RedisCounter counter;

    /**
     * 每个用例重建计数器；值操作走替身。
     */
    @BeforeEach
    void setUp() {
        counter = new RedisCounter(template);
    }

    /**
     * 自增返回脚本执行结果，TTL 按毫秒传给脚本。
     *
     * <p>守护 {@code increment}：脚本返回值与 TTL 换算改错即失败。
     */
    @Test
    @SuppressWarnings("unchecked")
    void incrementShouldReturnScriptResultWithTtlMillis() {
        when(template.execute(any(RedisScript.class), anyList(), any())).thenReturn(5L);

        long result = counter.increment("knowledge:auth:fail:ip:10.0.0.1", Duration.ofMinutes(15));

        assertEquals(5L, result);
        verify(template).execute(any(RedisScript.class), eq(List.of("knowledge:auth:fail:ip:10.0.0.1")),
                eq(String.valueOf(Duration.ofMinutes(15).toMillis())));
    }

    /**
     * 自增脚本未返回值时按 0 处理（脚本失败不抛错）。
     *
     * <p>守护 {@code increment} 的空值兜底：去掉 defaultIfNull 即失败（返回 null 拆箱抛 NPE）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void incrementShouldReturnZeroWhenScriptReturnsNull() {
        when(template.execute(any(RedisScript.class), anyList(), any())).thenReturn(null);

        assertEquals(0L, counter.increment("k", Duration.ofSeconds(1)));
    }

    /**
     * 键存在且值为数字时返回计数。
     *
     * <p>守护 {@code get} 的数字解析：解析改错即失败。
     */
    @Test
    void getShouldParseNumericValue() {
        when(template.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("k")).thenReturn("42");

        assertEquals(42L, counter.get("k"));
    }

    /**
     * 键缺失时按 0 处理。
     *
     * <p>守护 {@code get} 的空值兜底：去掉判空即失败（parse null 抛 NPE）。
     */
    @Test
    void getShouldReturnZeroWhenKeyMissing() {
        when(template.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("k")).thenReturn(null);

        assertEquals(0L, counter.get("k"));
    }

    /**
     * 值不是数字时按 0 处理（脏数据不抛错）。
     *
     * <p>守护 {@code get} 的非数字兜底：去掉 catch 即失败（NumberFormatException 上抛）。
     */
    @Test
    void getShouldReturnZeroWhenValueNotNumeric() {
        when(template.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("k")).thenReturn("abc");

        assertEquals(0L, counter.get("k"));
    }

    /**
     * 清零即删除计数键。
     *
     * <p>守护 {@code reset} 的边界调用：删键改成改值即失败。
     */
    @Test
    void resetShouldDeleteKey() {
        counter.reset("k");

        verify(template).delete("k");
        assertTrue(mockingDetails(template).getInvocations().stream()
                .anyMatch(inv -> "delete".equals(inv.getMethod().getName())));
    }
}
