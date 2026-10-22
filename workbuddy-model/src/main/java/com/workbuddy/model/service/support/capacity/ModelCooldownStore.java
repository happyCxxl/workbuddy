package com.workbuddy.model.service.support.capacity;

import com.workbuddy.model.ModelRef;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 冷却存储：命中 429 / 厂商不健康后写一个带 TTL 的键，所有实例共享。
 */
@Component
@RequiredArgsConstructor
public class ModelCooldownStore {

    /** 冷却键前缀 */
    private static final String KEY_PREFIX = "model:cooldown:";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 是否在冷却中。
     *
     * @param ref 模型定位
     * @return 冷却中为 true
     */
    public boolean coolingDown(ModelRef ref) {
        return stringRedisTemplate.hasKey(key(ref));
    }

    /**
     * 标记冷却（已存在则刷新 TTL）。
     *
     * @param ref      模型定位
     * @param duration 冷却时长；空或非正数忽略
     */
    public void markCooldown(ModelRef ref, Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        stringRedisTemplate.opsForValue().set(key(ref), "1", duration);
    }

    private String key(ModelRef ref) {
        return KEY_PREFIX + ref.vendorCode() + ":" + ref.modelKey();
    }
}
