package com.workbuddy.model.config;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模型路由参数（`knowledge.model.route` 前缀）：三信号打分权重、窗口、熔断与恢复时长。
 *
 * <p>整套参数有默认值，整段缺省即可启动；取值超出允许区间由 {@link ModelRouteValidator} 在启动期拒绝。
 * 参数只有一个来源（配置文件），行内不做参数页面，也不做多套策略与灰度。
 *
 * <p>健康面的三个键映射到熔断器：`error-trigger-ratio` 换算成失败率阈值百分数，
 * `error-window-seconds` 是熔断器的时间窗秒数，`cooldown-seconds` 是熔断打开后的保持时长。
 *
 * <p>时长一律按秒配置：窗口项带同名的毫秒只读视图，打开保持时长与爬升时长由消费方在装配时换算成毫秒。
 * 配置面不设毫秒键，也不设只作测试用途的字段；时间行为的用例自建熔断参数，见 `ModelCircuitBreakers` 的装配入口。
 *
 * @author cxxl
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.model.route")
public class ModelRouteProperties {

    /** 容量占用权重默认值 */
    private static final double DEFAULT_BETA_CAPACITY = 1.0D;

    /** 延迟权重默认值 */
    private static final double DEFAULT_BETA_LATENCY = 0.5D;

    /** 延迟窗口默认值（秒） */
    private static final int DEFAULT_LATENCY_WINDOW_SECONDS = 300;

    /** 占用越线比例默认值 */
    private static final double DEFAULT_CAPACITY_TRIGGER_RATIO = 0.8D;

    /** 错误率越线比例默认值 */
    private static final double DEFAULT_ERROR_TRIGGER_RATIO = 0.05D;

    /** 错误率统计窗口默认值（秒） */
    private static final int DEFAULT_ERROR_WINDOW_SECONDS = 60;

    /** 冷却时长默认值（秒） */
    private static final int DEFAULT_COOLDOWN_SECONDS = 60;

    /** 恢复放行爬升时长默认值（秒） */
    private static final int DEFAULT_SLOW_START_SECONDS = 45;

    /** 每秒毫秒数：窗口与时长按秒配置，内部按毫秒比较 */
    public static final long MILLIS_PER_SECOND = 1000L;

    /** 容量占用权重：占用越高扣分越重，0 = 容量不参与打分 */
    private double betaCapacity = DEFAULT_BETA_CAPACITY;

    /** 延迟权重：0 = 只看占用 */
    private double betaLatency = DEFAULT_BETA_LATENCY;

    /** 延迟 EWMA 的滑动窗口时长（秒） */
    private int latencyWindowSeconds = DEFAULT_LATENCY_WINDOW_SECONDS;

    /** 占用越线比例：占用率达到该值即视为接近上限，取值区间 (0, 1] */
    private double capacityTriggerRatio = DEFAULT_CAPACITY_TRIGGER_RATIO;

    /** 错误率越线比例：乘 100 即熔断器的失败率阈值百分数，取值区间 (0, 1] */
    private double errorTriggerRatio = DEFAULT_ERROR_TRIGGER_RATIO;

    /** 错误率统计窗口时长（秒）：熔断器按该长度的时间窗统计失败率 */
    private int errorWindowSeconds = DEFAULT_ERROR_WINDOW_SECONDS;

    /** 熔断打开保持时长（秒）：打开期间该模型不进候选，到期放行一次探测 */
    private int cooldownSeconds = DEFAULT_COOLDOWN_SECONDS;

    /** 进入半开后的放行比例爬升时长（秒） */
    private int slowStartSeconds = DEFAULT_SLOW_START_SECONDS;

    /** 延迟窗口（毫秒） */
    public long latencyWindowMillis() {
        return toMillis(latencyWindowSeconds);
    }

    /** 错误率窗口（毫秒） */
    public long errorWindowMillis() {
        return toMillis(errorWindowSeconds);
    }

    /**
     * 参数校验：权重非负；比例落在 (0, 1]；时长非负。
     * 每项校验都带配置键全名，越线即抛知识异常终止启动。
     */
    public void validate() {
        ThrowUtil.throwIf(betaCapacity < 0D, ErrorCode.PARAM_INVALID,
                "beta-capacity 不能为负数，当前值 " + betaCapacity + "（配置键 " + key("beta-capacity") + "）");
        ThrowUtil.throwIf(betaLatency < 0D, ErrorCode.PARAM_INVALID,
                "beta-latency 不能为负数，当前值 " + betaLatency + "（配置键 " + key("beta-latency") + "）");
        checkRatio("capacity-trigger-ratio", capacityTriggerRatio);
        checkRatio("error-trigger-ratio", errorTriggerRatio);
        checkDuration("latency-window-seconds", latencyWindowSeconds);
        checkDuration("error-window-seconds", errorWindowSeconds);
        checkDuration("cooldown-seconds", cooldownSeconds);
        checkDuration("slow-start-seconds", slowStartSeconds);
    }

    /** 比例校验：落在 (0, 1] 区间 */
    private void checkRatio(String name, double value) {
        ThrowUtil.throwIf(value <= 0D || value > 1D, ErrorCode.PARAM_INVALID,
                name + " 必须落在 (0, 1] 区间，当前值 " + value + "（配置键 " + key(name) + "）");
    }

    /** 时长校验：非负整数秒 */
    private void checkDuration(String name, int seconds) {
        ThrowUtil.throwIf(seconds < 0, ErrorCode.PARAM_INVALID,
                name + " 不能为负数，当前值 " + seconds + "（配置键 " + key(name) + "）");
    }

    /** 秒 → 毫秒；入参为负按零处理，启动期校验已拦下负数 */
    private long toMillis(int seconds) {
        return Math.max(seconds, 0) * MILLIS_PER_SECOND;
    }

    /** 配置键全名（错误信息里指出改哪一项） */
    private String key(String name) {
        return "knowledge.model.route." + name;
    }
}
