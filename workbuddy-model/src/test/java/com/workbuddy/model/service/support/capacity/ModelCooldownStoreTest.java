package com.workbuddy.model.service.support.capacity;

import com.workbuddy.model.ModelRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 冷却存储单测：键存在即冷却、标记写 TTL、非正时长忽略。
 */
@ExtendWith(MockitoExtension.class)
class ModelCooldownStoreTest {

    private static final ModelRef REF = new ModelRef("dashscope", "qwen-max");

    private static final String KEY = "model:cooldown:dashscope:qwen-max";

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    /** 键存在即冷却中（{@code shouldCheckCoolingDown}）。 */
    @Test
    void shouldCheckCoolingDown() {
        when(stringRedisTemplate.hasKey(KEY)).thenReturn(true);

        assertTrue(new ModelCooldownStore(stringRedisTemplate).coolingDown(REF));
    }

    /** 键不存在即未冷却（{@code shouldNotCoolWhenKeyAbsent}）。 */
    @Test
    void shouldNotCoolWhenKeyAbsent() {
        when(stringRedisTemplate.hasKey(KEY)).thenReturn(false);

        assertFalse(new ModelCooldownStore(stringRedisTemplate).coolingDown(REF));
    }

    /** 标记冷却：写键并带 TTL（{@code shouldMarkWithTtl}）。 */
    @Test
    void shouldMarkWithTtl() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        Duration cooldown = Duration.ofSeconds(30);

        new ModelCooldownStore(stringRedisTemplate).markCooldown(REF, cooldown);

        verify(valueOperations).set(KEY, "1", cooldown);
    }

    /** 非正时长忽略（{@code shouldIgnoreNonPositiveDuration}）。 */
    @Test
    void shouldIgnoreNonPositiveDuration() {
        new ModelCooldownStore(stringRedisTemplate).markCooldown(REF, Duration.ZERO);

        verifyNoInteractions(stringRedisTemplate);
    }
}
