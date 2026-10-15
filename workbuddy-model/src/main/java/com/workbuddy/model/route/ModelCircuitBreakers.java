package com.workbuddy.model.route;

import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.config.ModelRouteProperties;
import com.workbuddy.model.invoke.ModelErrorClassifier;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 模型熔断器：按模型（厂商码 + 模型名）各持有一个 Resilience4j 熔断器，错误率统计、打开保持与半开探测都交给它。
 *
 * <p>维度按模型算：一个模型声明多个能力时共用同一个熔断器（健康不按「模型 × 能力」重复计数）。
 * 状态落在单副本进程内（`CircuitBreakerRegistry` + 并发映射），多副本部署时把本类换成共享状态实现，
 * 方法签名保持不变。
 *
 * <p>参数映射：`error-trigger-ratio` 乘 100 即失败率阈值百分数，`error-window-seconds` 是时间窗秒数，
 * `cooldown-seconds` 是打开保持时长，`slow-start-seconds` 是转入半开后的放行比例爬升时长。
 * 半开只放行一次探测；打开保持到期由库的调度线程自动转换推进，取许可时也按同一时钟判定到期。
 * 时间源是熔断器实例自己的系统时钟（库的公开入口不接受外部时钟），恢复放行爬升段按它读取经过时长。
 *
 * <p>失败口径：只有厂商侧不健康的失败（限流 / 服务端错误 / 超时 / 连接失败 / DNS 不可达）计入失败率，
 * 鉴权、参数、内容拒绝、模型不存在与输出结构校验失败按成功记，不把模型推到打开状态；
 * 该判断由 {@link ModelErrorClassifier#isVendorUnhealthy(Throwable)} 一处给出，与「可降级」是两条独立口径。
 *
 * <p>取许可与归还：调用链先经 {@link #tryAcquire} 取一次许可，取到而未真正发起调用的候选由
 * {@link #release} 归还，真实调用前再取一次；半开探测的放行条数不会让没被调用的候选用掉。
 *
 * @author cxxl
 */
@Slf4j
@Component
public class ModelCircuitBreakers {

    /** 统计失败率所需的最少调用条数：时间窗内不足该条数时不判越线 */
    private static final int MINIMUM_NUMBER_OF_CALLS = 10;

    /** 半开状态放行的探测条数 */
    private static final int HALF_OPEN_PERMITS = 1;

    /** 恢复放行比例的起点：转入半开后首次放行的比例 */
    private static final double SLOW_START_START_RATIO = 0.2D;

    /** 放行比例的满量值 */
    private static final double FULL_RATIO = 1.0D;

    /** 比例换算成百分数的基数 */
    private static final double FULL_PERCENT = 100D;

    /** 百分数取整容差：消除浮点乘法的末位噪声，让 0.05 换算出 5 而不是 5.000000000000001 */
    private static final double PERCENT_EPSILON = 1.0E-6D;

    /** 失败率阈值下限：库要求阈值大于零 */
    private static final double MIN_FAILURE_RATE_PERCENT = 0.01D;

    /** 时间窗秒数下限：库要求窗口大于零 */
    private static final int MIN_WINDOW_SECONDS = 1;

    /** 打开保持时长下限（毫秒）：库要求至少一毫秒 */
    private static final long MIN_WAIT_MILLIS = 1L;

    /** 熔断器标签：本类按模型名建实例，不额外挂标签 */
    private static final Map<String, String> NO_TAGS = Map.of();

    /** 状态键分隔符：厂商码与模型名都不含竖线 */
    private static final String KEY_SEPARATOR = "|";

    /** 生效的熔断参数：全部熔断器共用一份 */
    private final CircuitBreakerConfig config;

    /** 生效的恢复放行爬升时长（毫秒）：由 `slow-start-seconds` 换算得到 */
    private final long slowStartMillis;

    /** 熔断器注册表：按模型名取或建实例，缺键创建由库的公开入口承担 */
    private final CircuitBreakerRegistry registry;

    /** 最近一次从打开转入半开的时刻（毫秒）：恢复放行爬升段的起点 */
    private final ConcurrentHashMap<String, Long> halfOpenStartedAt = new ConcurrentHashMap<>();

    /**
     * 装配入口：熔断器的打开保持与半开到期都按系统时钟判定，恢复放行爬升段按同一时钟读取经过时长。
     *
     * @param properties 路由参数
     * @param classifier 错误分类：失败判定谓词取自它
     */
    @Autowired
    public ModelCircuitBreakers(ModelRouteProperties properties, ModelErrorClassifier classifier) {
        this(configFor(properties.getErrorTriggerRatio(), properties.getErrorWindowSeconds(), cooldownMillis(properties),
                classifier), slowStartMillis(properties));
    }

    /**
     * 装配入口（显式参数）：熔断参数与爬升时长由入参给出，打开保持与爬升段按毫秒取值。
     * 时间行为用例据此把两段窗口收到百毫秒级，配置面不必为用例开口子。
     *
     * @param config           生效的熔断参数
     * @param slowStartMillis  恢复放行爬升时长（毫秒）
     */
    ModelCircuitBreakers(CircuitBreakerConfig config, long slowStartMillis) {
        this.config = config;
        this.slowStartMillis = slowStartMillis;
        this.registry = CircuitBreakerRegistry.of(config);
        registry.getEventPublisher().onEntryAdded(entry -> attach(entry.getAddedEntry()));
    }

    /**
     * 取一次调用许可：打开期间拒绝；打开保持到期后的首次调用转入半开，只放行一次探测。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @return 本次放行为 true
     */
    public boolean tryAcquire(String vendorCode, String modelKey) {
        return breaker(vendorCode, modelKey).tryAcquirePermission();
    }

    /**
     * 归还一次许可：许可已取到而调用没有发出时调用，半开探测名额回到可用状态。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     */
    public void release(String vendorCode, String modelKey) {
        breaker(vendorCode, modelKey).releasePermission();
    }

    /**
     * 记一次成功：半开探测成功即关闭熔断器，计数清零。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     */
    public void onSuccess(String vendorCode, String modelKey) {
        breaker(vendorCode, modelKey).onSuccess(0L, TimeUnit.NANOSECONDS);
    }

    /**
     * 记一次失败：是否计入失败率由配置里的谓词判定，厂商侧健康的失败按成功记，不改变熔断状态。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @param cause      本次失败的原因（映射后的知识异常或适配器原始异常）
     */
    public void onError(String vendorCode, String modelKey, Throwable cause) {
        breaker(vendorCode, modelKey).onError(0L, TimeUnit.NANOSECONDS, cause);
    }

    /**
     * 当前状态：关闭 / 打开 / 半开。打开保持到期由库的调度线程推进到半开，本方法只读当前值。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @return 熔断器状态
     */
    public CircuitBreaker.State state(String vendorCode, String modelKey) {
        return breaker(vendorCode, modelKey).getState();
    }

    /**
     * 最近一次从打开转入半开的时刻（毫秒）：恢复放行爬升段的起点；没转入过为 0。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @return 毫秒时刻；无记录为 0
     */
    public long halfOpenStartedAt(String vendorCode, String modelKey) {
        Long startedAt = halfOpenStartedAt.get(key(vendorCode, modelKey));
        return NullUtil.isNull(startedAt) ? 0L : startedAt;
    }

    /**
     * 是否处于恢复放行段：半开探测段，或最近一次转入半开之后的 `slow-start-seconds` 内。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @return 恢复放行段为 true
     */
    public boolean inSlowStart(String vendorCode, String modelKey) {
        return state(vendorCode, modelKey) == CircuitBreaker.State.HALF_OPEN
                || admissionRatio(vendorCode, modelKey) < FULL_RATIO;
    }

    /**
     * 放行比例：转入半开起按 `slow-start-seconds` 从 20% 线性升到 100%；没转入过或爬升已满都按满量。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @return 放行比例（0 ~ 1）
     */
    public double admissionRatio(String vendorCode, String modelKey) {
        Long startedAt = halfOpenStartedAt.get(key(vendorCode, modelKey));
        if (NullUtil.isNull(startedAt) || slowStartMillis <= 0L) {
            return FULL_RATIO;
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        if (elapsed >= slowStartMillis) {
            return FULL_RATIO;
        }
        double climbed = SLOW_START_START_RATIO + (FULL_RATIO - SLOW_START_START_RATIO) * elapsed / slowStartMillis;
        return Math.min(climbed, FULL_RATIO);
    }

    /** 生效的熔断参数（用例读取实际生效值） */
    public CircuitBreakerConfig configuration() {
        return config;
    }

    /** 生效的恢复放行爬升时长（毫秒）：`slow-start-seconds` 的换算结果 */
    public long slowStartMillis() {
        return slowStartMillis;
    }

    /**
     * 该模型的熔断器实例：读数与状态迁移都按它读或做（打开 / 半开 / 关闭三类前提按库的公开迁移方法构造），
     * 缺键即建一份关闭状态。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @return 该模型的熔断器
     */
    public CircuitBreaker breaker(String vendorCode, String modelKey) {
        return registry.circuitBreaker(key(vendorCode, modelKey), config, NO_TAGS);
    }

    /** 新熔断器入表：挂上状态流转监听，转入半开时记下恢复爬升段的起点 */
    private void attach(CircuitBreaker entry) {
        entry.getEventPublisher().onStateTransition(event -> onStateTransition(entry.getName(),
                event.getStateTransition()));
    }

    /** 状态流转留痕：转入半开记下爬升起点，三段流转各记一条日志 */
    private void onStateTransition(String name, CircuitBreaker.StateTransition transition) {
        CircuitBreaker.State toState = transition.getToState();
        if (toState == CircuitBreaker.State.HALF_OPEN) {
            halfOpenStartedAt.put(name, System.currentTimeMillis());
            log.info("模型熔断进入半开, 熔断器=[{}]", name);
            return;
        }
        if (toState == CircuitBreaker.State.OPEN) {
            log.warn("模型熔断打开, 熔断器=[{}]", name);
            return;
        }
        if (toState == CircuitBreaker.State.CLOSED) {
            log.info("模型熔断关闭, 熔断器=[{}]", name);
        }
    }

    /**
     * 熔断参数：阈值、时间窗、最少调用数、打开保持时长、半开探测条数与自动转半开都按入参映射。
     * 时间戳函数取熔断器实例自己的时钟，与打开保持判定用的是同一个时间源。
     *
     * <p>打开保持时长与时间窗按毫秒、秒给出：时间行为用例调用本方法时把保持时长收到百毫秒级，
     * 与生产路径共用同一份映射代码。
     *
     * @param errorTriggerRatio 错误率越线比例（0 ~ 1）
     * @param errorWindowSeconds 错误率统计窗口（秒）
     * @param cooldownMillis     打开保持时长（毫秒）
     * @param classifier         错误分类：失败判定谓词取自它
     * @return 生效的熔断参数
     */
    static CircuitBreakerConfig configFor(double errorTriggerRatio, int errorWindowSeconds, long cooldownMillis,
            ModelErrorClassifier classifier) {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold(errorTriggerRatio))
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.TIME_BASED)
                .slidingWindowSize(Math.max(errorWindowSeconds, MIN_WINDOW_SECONDS))
                .minimumNumberOfCalls(MINIMUM_NUMBER_OF_CALLS)
                .waitDurationInOpenState(Duration.ofMillis(Math.max(cooldownMillis, MIN_WAIT_MILLIS)))
                .permittedNumberOfCallsInHalfOpenState(HALF_OPEN_PERMITS)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(classifier::isVendorUnhealthy)
                .currentTimestampFunction(instanceClock -> instanceClock.millis(), TimeUnit.MILLISECONDS)
                .build();
    }

    /** 失败率阈值百分数：越线比例乘 100，与整数百分数对齐 */
    private static float failureRateThreshold(double errorTriggerRatio) {
        double percent = errorTriggerRatio * FULL_PERCENT;
        double rounded = Math.rint(percent);
        double snapped = Math.abs(percent - rounded) < PERCENT_EPSILON ? rounded : percent;
        return (float) Math.max(snapped, MIN_FAILURE_RATE_PERCENT);
    }

    /** 打开保持时长（毫秒）：由 `cooldown-seconds` 换算，库要求至少一毫秒 */
    private static long cooldownMillis(ModelRouteProperties properties) {
        return secondsToMillis(properties.getCooldownSeconds());
    }

    /** 恢复放行爬升时长（毫秒）：由 `slow-start-seconds` 换算 */
    private static long slowStartMillis(ModelRouteProperties properties) {
        return secondsToMillis(properties.getSlowStartSeconds());
    }

    /** 秒 → 毫秒：入参为负按零处理，启动期校验已拦下负数 */
    private static long secondsToMillis(int seconds) {
        return Math.max(seconds, 0) * ModelRouteProperties.MILLIS_PER_SECOND;
    }

    /** 状态键：厂商码 + 分隔符 + 模型名 */
    private String key(String vendorCode, String modelKey) {
        return vendorCode + KEY_SEPARATOR + modelKey;
    }
}
