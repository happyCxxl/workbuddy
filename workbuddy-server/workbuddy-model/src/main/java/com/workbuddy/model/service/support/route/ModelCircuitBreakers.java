package com.workbuddy.model.service.support.route;

import com.workbuddy.model.ModelRef;
import com.workbuddy.model.config.ModelRouteProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 模型级熔断器：按 {@link ModelRef} 维护 Resilience4j 实例，进程内、各实例独立。
 *
 * <p>失败率与窗口取 `workbuddy.model.route.*`；半开只放行一次探测，冷却后自动转半开。
 */
@Component
public class ModelCircuitBreakers {

    /** 熔断最少调用数：低于该数不做失败率判断 */
    private static final int MINIMUM_NUMBER_OF_CALLS = 10;

    /** 半开探测放行数 */
    private static final int HALF_OPEN_PERMITTED_CALLS = 1;

    private final CircuitBreakerRegistry registry;

    private final Map<ModelRef, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    /**
     * 按配置构建熔断注册表。
     *
     * @param properties 路由配置
     */
    public ModelCircuitBreakers(ModelRouteProperties properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold((float) (properties.getErrorTriggerRatio() * 100))
                .slidingWindowType(SlidingWindowType.TIME_BASED)
                .slidingWindowSize((int) properties.getErrorWindowSeconds())
                .minimumNumberOfCalls(MINIMUM_NUMBER_OF_CALLS)
                .waitDurationInOpenState(Duration.ofSeconds(properties.getCooldownSeconds()))
                .permittedNumberOfCallsInHalfOpenState(HALF_OPEN_PERMITTED_CALLS)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        this.registry = CircuitBreakerRegistry.of(config);
    }

    /**
     * 是否处于打开状态（打开期间路由不再选它）。
     *
     * @param ref 模型定位
     * @return 打开为 true
     */
    public boolean isOpen(ModelRef ref) {
        return breaker(ref).getState() == CircuitBreaker.State.OPEN;
    }

    /**
     * 取一次调用许可：关闭态直接放行，半开态只放一个探测。
     *
     * @param ref 模型定位
     * @return 放行为 true
     */
    public boolean tryAcquire(ModelRef ref) {
        return breaker(ref).tryAcquirePermission();
    }

    /**
     * 归还许可：准入被容量拒绝时调用（不算失败，保证半开计数正确）。
     *
     * @param ref 模型定位
     */
    public void releasePermission(ModelRef ref) {
        breaker(ref).releasePermission();
    }

    /**
     * 记录成功。
     *
     * @param ref       模型定位
     * @param latencyMs 耗时（毫秒）
     */
    public void onSuccess(ModelRef ref, long latencyMs) {
        breaker(ref).onSuccess(latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录失败（只对厂商侧不健康失败调用）。
     *
     * @param ref       模型定位
     * @param latencyMs 耗时（毫秒）
     * @param error     原始异常
     */
    public void onFailure(ModelRef ref, long latencyMs, Throwable error) {
        breaker(ref).onError(latencyMs, TimeUnit.MILLISECONDS, error);
    }

    private CircuitBreaker breaker(ModelRef ref) {
        return breakers.computeIfAbsent(ref,
                key -> registry.circuitBreaker(key.vendorCode() + ":" + key.modelKey()));
    }
}
