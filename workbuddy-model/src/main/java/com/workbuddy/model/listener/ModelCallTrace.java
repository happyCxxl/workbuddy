package com.workbuddy.model.listener;

import com.workbuddy.common.domain.model.ModelUsage;
import com.workbuddy.common.enums.model.RouteReason;
import com.workbuddy.common.error.ErrorCode;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次模型调用的可变上下文：承载尝试次数、用量、延迟、错误与路由原因，随一次调用创建，调用结束后读取。
 * 尝试次数由 HTTP 客户端装饰器在真实请求层写入，用量、延迟与错误由监听器写入，
 * 路由原因与降级来源由路由与调用链接入点写入；不作为对外契约，只在 knowledge-model 内部流转。
 *
 * @author cxxl
 */
public class ModelCallTrace {

    /** 请求选项属性表里的键，监听器按此键取回本对象 */
    public static final String ATTRIBUTE_KEY = "knowledge.modelCallTrace";

    /** 纳秒到毫秒的换算基数 */
    private static final long NANOS_PER_MILLI = 1_000_000L;

    /** 真实 HTTP 尝试次数：每次请求发出前加一，重试各计一次 */
    private final AtomicInteger attempts = new AtomicInteger();

    /** 首次请求的时间戳（纳秒），0 表示尚未记录 */
    private final AtomicLong startedAtNanos = new AtomicLong();

    /** 最近一次尝试的耗时（毫秒）；未记录为 null */
    private volatile Long lastAttemptLatencyMs;

    /** 用量；厂商未回 usage 时为 null */
    private volatile ModelUsage usage;

    /** 延迟（毫秒）；未收到响应与错误时为 null */
    private volatile Long latencyMs;

    /** 失败错误码；成功时为 null */
    private volatile ErrorCode errorCode;

    /** 失败时的原始异常；成功时为 null */
    private volatile Throwable error;

    /** 路由原因；不经营由选型的调用为 null */
    private volatile RouteReason routeReason;

    /** 由哪个模型降级而来；未发生降级为 null */
    private volatile String fallbackFrom;

    /** 尝试次数加一：每次真实 HTTP 请求发出前调用一次 */
    public void increaseAttempts() {
        attempts.incrementAndGet();
    }

    /**
     * 记录最近一次尝试的耗时。
     *
     * @param elapsedNanos 该次尝试的纳秒耗时
     */
    public void markAttemptLatency(long elapsedNanos) {
        lastAttemptLatencyMs = elapsedNanos / NANOS_PER_MILLI;
    }

    /** 记录首次请求的时间戳（只记第一次） */
    public void markRequestStart() {
        startedAtNanos.compareAndSet(0L, System.nanoTime());
    }

    /** 记录从首次请求到本次调用的耗时（毫秒）；未记录开始时间即不写 */
    public void markLatency() {
        long startedAt = startedAtNanos.get();
        if (startedAt > 0L) {
            latencyMs = (System.nanoTime() - startedAt) / NANOS_PER_MILLI;
        }
    }

    /** 写入用量 */
    public void setUsage(ModelUsage usage) {
        this.usage = usage;
    }

    /** 写入失败错误码 */
    public void setErrorCode(ErrorCode errorCode) {
        this.errorCode = errorCode;
    }

    /** 写入失败时的原始异常 */
    public void setError(Throwable error) {
        this.error = error;
    }

    /** 写入本次调用的路由原因 */
    public void setRouteReason(RouteReason routeReason) {
        this.routeReason = routeReason;
    }

    /** 写入本次调用的降级来源模型名 */
    public void setFallbackFrom(String fallbackFrom) {
        this.fallbackFrom = fallbackFrom;
    }

    /** 尝试次数：真实 HTTP 请求次数 */
    public int attempts() {
        return attempts.get();
    }

    /** 重试次数：尝试次数 − 1，下限 0 */
    public int retryCount() {
        return Math.max(attempts.get() - 1, 0);
    }

    /** 最近一次尝试的耗时（毫秒） */
    public Long lastAttemptLatencyMs() {
        return lastAttemptLatencyMs;
    }

    /** 用量 */
    public ModelUsage usage() {
        return usage;
    }

    /** 延迟（毫秒） */
    public Long latencyMs() {
        return latencyMs;
    }

    /** 失败错误码 */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 失败时的原始异常 */
    public Throwable error() {
        return error;
    }

    /** 路由原因；不经营由选型的调用为 null */
    public RouteReason routeReason() {
        return routeReason;
    }

    /** 由哪个模型降级而来；未发生降级为 null */
    public String fallbackFrom() {
        return fallbackFrom;
    }
}
