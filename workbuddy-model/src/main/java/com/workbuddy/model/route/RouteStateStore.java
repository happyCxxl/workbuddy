package com.workbuddy.model.route;

import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.config.ModelRouteProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 路由状态存储：按模型（厂商码 + 模型名）维护在途请求数、延迟 EWMA 与占用计数（RPM / TPM 窗口），
 * 另记上次选中时刻与最近一次失败的错误码供排障读取。
 *
 * <p>健康面（错误率统计、熔断打开与半开、恢复放行）由 {@link ModelCircuitBreakers} 承担，
 * 本类只留打分与观测要用的读数，不持有冷却与窗口状态。
 *
 * <p>维度按模型算：一个模型声明多个能力时共用一份状态（限额与读数都不按「模型 × 能力」重复计数）。
 * 状态落在单副本进程内（`ConcurrentHashMap` + 原子量 + 同步块），多副本部署时把本类换成 Redis 实现，
 * 方法签名保持不变；本类不引入任何外部存储依赖。
 *
 * <p>延迟口径：只取成功调用的耗时并带时间衰减更新，窗口时长超过 `latency-window-seconds` 的旧样本不再参与。
 * 占用计数的窗口固定为每分钟，限额留空的模型不参与占用判断。
 *
 * <p>占用计数的写入落在每次真实调用结束后（由 {@link com.workbuddy.model.invoke.ModelInvoker} 调用
 * {@link #record}）：一次真实调用记一条样本，样本里是一次请求与本次消耗的 token 合计；
 * token 取调用上下文的用量，厂商未回用量时由调用链按请求文本长度粗估一个值补上（只用于占用判断）。
 *
 * @author cxxl
 */
@Component
public class RouteStateStore {

    /** 滑动窗口内保留的延迟样本条数下限 */
    private static final int MIN_LATENCY_SAMPLES = 8;

    /** 占用率窗口：RPM 与 TPM 都是每分钟口径 */
    private static final long OCCUPANCY_WINDOW_MILLIS = 60_000L;

    /** 路由参数 */
    private final ModelRouteProperties properties;

    /** 当前时间来源；用例注入可推进的时间源 */
    private final TimeSource timeSource;

    /** 模型状态（键 = 厂商码|模型名） */
    private final ConcurrentHashMap<String, ModelState> states = new ConcurrentHashMap<>();

    /**
     * 装配入口：时间源取系统时钟。
     *
     * @param properties 路由参数
     */
    @Autowired
    public RouteStateStore(ModelRouteProperties properties) {
        this(properties, TimeSource.DEFAULT);
    }

    /**
     * 指定时间源的存储：供用例推进时间。
     *
     * @param properties 路由参数
     * @param timeSource 当前时间来源（毫秒）
     */
    public RouteStateStore(ModelRouteProperties properties, TimeSource timeSource) {
        this.properties = properties;
        this.timeSource = timeSource;
    }

    /**
     * 记一次真实调用：按占用口径写入一条样本（一次请求 + 本次消耗的 token 合计），成功写入延迟样本与 EWMA，
     * 失败记下错误码；两者都累计调用条数。每条样本即一次消耗的请求数，窗口内样本条数即 RPM 读数。
     * 健康判定不在这里做。
     *
     * @param vendorCode      厂商码
     * @param modelKey        模型名
     * @param success         本次调用是否成功
     * @param latencyMs       本次调用耗时（毫秒，可空）
     * @param errorCodeText   失败错误码名（成功传空）
     * @param totalTokens     本次调用的 token 合计（提示词 + 生成，可空；只作 TPM 占用读数）
     * @param occupancySample 本次调用是否计入占用窗口（真 = 计入；形参与调用前检查拦下的调用不计）
     */
    public void record(String vendorCode, String modelKey, boolean success, Long latencyMs, String errorCodeText,
            Long totalTokens, boolean occupancySample) {
        ModelState state = state(vendorCode, modelKey);
        long now = timeSource.now();
        synchronized (state) {
            if (occupancySample) {
                state.samples.addLast(new CallSample(now, totalTokens));
            }
            pruneSamples(state, now);
            state.total++;
            if (success) {
                if (NullUtil.isNotNull(latencyMs) && latencyMs > 0L) {
                    state.latencySamples.addLast(new LatencySample(now, latencyMs));
                    pruneLatency(state, now);
                    updateLatencyEwma(state, latencyMs, now);
                }
                return;
            }
            state.failures++;
            state.lastErrorCode = errorCodeText;
        }
    }

    /**
     * 记一次选型：更新模型的上次选中时间，供排障读取切换节奏。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     */
    public void recordSelection(String vendorCode, String modelKey) {
        state(vendorCode, modelKey).selectedAt.set(timeSource.now());
    }

    /**
     * 进入一次在途调用：计数加一。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     */
    public void enter(String vendorCode, String modelKey) {
        state(vendorCode, modelKey).inFlight.incrementAndGet();
    }

    /**
     * 退出一次在途调用：计数减一，下限为零。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     */
    public void exit(String vendorCode, String modelKey) {
        state(vendorCode, modelKey).inFlight.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    /**
     * 取当前状态快照：各窗口都按当前时刻裁剪，返回的是裁剪后的读数。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @return 该模型的状态快照
     */
    public Snapshot snapshot(String vendorCode, String modelKey) {
        ModelState state = state(vendorCode, modelKey);
        long now = timeSource.now();
        synchronized (state) {
            pruneSamples(state, now);
            pruneLatency(state, now);
            return new Snapshot(vendorCode, modelKey, state.inFlight.get(), state.samples.size(), tokensInWindow(state),
                    state.latencyEwma, state.latencySamples.size(), state.total, state.failures, state.selectedAt.get(),
                    state.lastErrorCode);
        }
    }

    /** 延迟 EWMA：按与上次更新的间隔做时间衰减，间隔越长权重越低 */
    private void updateLatencyEwma(ModelState state, long latencyMs, long now) {
        if (state.latencyEwma <= 0D) {
            state.latencyEwma = latencyMs;
            state.latencyUpdatedAt = now;
            return;
        }
        long intervalMillis = Math.max(properties.latencyWindowMillis() / samplesPerWindow(), 1L);
        long gap = Math.max(now - state.latencyUpdatedAt, 0L);
        double alpha = 1D - Math.exp(-(double) gap / intervalMillis);
        state.latencyEwma = alpha * latencyMs + (1D - alpha) * state.latencyEwma;
        state.latencyUpdatedAt = now;
    }

    /** 窗口内的目标样本条数：按每秒一条估算，下限 {@link #MIN_LATENCY_SAMPLES} 条 */
    private int samplesPerWindow() {
        return (int) Math.max(properties.latencyWindowMillis() / ModelRouteProperties.MILLIS_PER_SECOND,
                MIN_LATENCY_SAMPLES);
    }

    /** 裁剪占用样本：超出每分钟窗口或超出条数上限的旧样本都丢弃 */
    private void pruneSamples(ModelState state, long now) {
        long floor = now - OCCUPANCY_WINDOW_MILLIS;
        while (!state.samples.isEmpty() && state.samples.peekFirst().at() < floor) {
            state.samples.removeFirst();
        }
        while (state.samples.size() > samplesPerWindow() * 2) {
            state.samples.removeFirst();
        }
    }

    /** 裁剪延迟样本：窗口外的旧样本丢弃，窗口内样本过多时按条数上限丢弃最早的 */
    private void pruneLatency(ModelState state, long now) {
        long floor = now - properties.latencyWindowMillis();
        while (!state.latencySamples.isEmpty() && state.latencySamples.peekFirst().at() < floor) {
            state.latencySamples.removeFirst();
        }
        while (state.latencySamples.size() > samplesPerWindow()) {
            state.latencySamples.removeFirst();
        }
    }

    /** 窗口内 token 合计（TPM 占用口径） */
    private long tokensInWindow(ModelState state) {
        long tokens = 0L;
        for (CallSample sample : state.samples) {
            if (NullUtil.isNotNull(sample.tokens())) {
                tokens += sample.tokens();
            }
        }
        return tokens;
    }

    /** 取模型状态；首次访问即建一份空状态 */
    private ModelState state(String vendorCode, String modelKey) {
        return states.computeIfAbsent(key(vendorCode, modelKey), cacheKey -> new ModelState());
    }

    /** 状态键：厂商码 + 分隔符 + 模型名（两类标识都不含竖线） */
    private String key(String vendorCode, String modelKey) {
        return vendorCode + "|" + modelKey;
    }

    /** 一次调用的占用样本：时刻与本次调用的 token 合计 */
    private record CallSample(long at, Long tokens) {
    }

    /** 一次成功调用的延迟样本：时刻与耗时 */
    private record LatencySample(long at, long latencyMs) {
    }

    /**
     * 单个模型的状态快照：打分与观测要用的读数。
     *
     * @param vendorCode     厂商码
     * @param modelKey       模型名
     * @param inFlight       当前在途请求数
     * @param rpmCount       每分钟窗口内的调用条数
     * @param tpmTokens      每分钟窗口内的 token 合计
     * @param latencyEwmaMs  延迟 EWMA（毫秒）；无成功样本为 0
     * @param latencySamples 窗口内的延迟样本条数
     * @param totalCalls     累计调用条数
     * @param totalFailures  累计失败条数
     * @param lastSelectedAt 上次被选中的时刻（毫秒）；未选中过为 0
     * @param lastErrorCode  最近一次失败的错误码名；无失败为 null
     */
    public record Snapshot(String vendorCode, String modelKey, int inFlight, int rpmCount, long tpmTokens,
            double latencyEwmaMs, int latencySamples, int totalCalls, int totalFailures, long lastSelectedAt,
            String lastErrorCode) {
    }

    /** 单个模型的可变状态：所有读写都在本对象上同步 */
    private static final class ModelState {

        /** 在途请求数 */
        private final AtomicInteger inFlight = new AtomicInteger();

        /** 上次被选中的时刻（毫秒） */
        private final AtomicReference<Long> selectedAt = new AtomicReference<>(0L);

        /** 占用样本（按时刻升序） */
        private final Deque<CallSample> samples = new ArrayDeque<>();

        /** 延迟样本（按时刻升序） */
        private final Deque<LatencySample> latencySamples = new ArrayDeque<>();

        /** 延迟 EWMA（毫秒） */
        private double latencyEwma;

        /** 上次更新延迟 EWMA 的时刻（毫秒） */
        private long latencyUpdatedAt;

        /** 累计调用条数 */
        private int total;

        /** 累计失败条数 */
        private int failures;

        /** 最近一次失败的错误码名 */
        private String lastErrorCode;
    }
}
