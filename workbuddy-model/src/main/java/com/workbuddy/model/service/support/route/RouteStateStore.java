package com.workbuddy.model.service.support.route;

import com.workbuddy.model.ModelRef;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内路由状态：延迟 EWMA、首次见到时间与 slow start 系数。
 *
 * <p>只在单实例内近似；容量与冷却走 Redis（跨实例一致）。
 */
@Component
public class RouteStateStore {

    /** EWMA 平滑系数：新样本占 30% */
    private static final double ALPHA = 0.3;

    /** slow start 起步放行比例 */
    private static final double SLOW_START_FLOOR = 0.2;

    private final ConcurrentMap<ModelRef, Double> latencies = new ConcurrentHashMap<>();

    private final ConcurrentMap<ModelRef, Long> firstSeen = new ConcurrentHashMap<>();

    /**
     * 记录一次成功调用的耗时。
     *
     * @param ref         模型定位
     * @param latencyMillis 耗时（毫秒）
     */
    public void recordLatency(ModelRef ref, long latencyMillis) {
        latencies.merge(ref, (double) latencyMillis,
                (oldValue, newValue) -> ALPHA * newValue + (1 - ALPHA) * oldValue);
    }

    /**
     * 延迟 EWMA。
     *
     * @param ref 模型定位
     * @return 毫秒；未采样返回 0
     */
    public long latencyMillis(ModelRef ref) {
        Double value = latencies.get(ref);
        return value == null ? 0L : Math.round(value);
    }

    /**
     * slow start 放行系数：窗口内从 0.2 线性爬到 1.0。
     *
     * @param ref           模型定位
     * @param windowSeconds 窗口（秒），小于等于 0 时不限
     * @return 0.2~1.0 的系数
     */
    public double slowStartFactor(ModelRef ref, long windowSeconds) {
        if (windowSeconds <= 0) {
            return 1.0;
        }
        long seen = firstSeen.computeIfAbsent(ref, key -> System.currentTimeMillis());
        double elapsedSeconds = (System.currentTimeMillis() - seen) / 1000.0;
        if (elapsedSeconds >= windowSeconds) {
            return 1.0;
        }
        return SLOW_START_FLOOR + (1 - SLOW_START_FLOOR) * Math.max(0, elapsedSeconds) / windowSeconds;
    }
}
