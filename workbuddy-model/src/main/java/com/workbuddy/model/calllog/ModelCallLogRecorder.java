package com.workbuddy.model.calllog;

import com.workbuddy.common.domain.model.ModelUsage;
import com.workbuddy.common.domain.model.calllog.ModelCallLogEntry;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.model.CallStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.catalog.ModelCatalogPort;
import com.workbuddy.model.listener.ModelCallTrace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 调用明细组装与落库：把一次逻辑调用的结果、用量与分析出的成本组装成一行，交给 {@link ModelCallLogPort} 同步插入。
 *
 * <p>口径三条：
 * 用量未知（厂商未回 usage，或三项计数全空）时三项 token 与 cost 记 null 并置 {@code usageUnknown = 1}（D19），
 * 不写 -1、不写 0、不做本地估算；确实为零写 0，与未知区分。
 * 单价取自目录视图并随行留档，成本按「输入 token × 输入单价 + 生成 token × 输出单价」折算；
 * 目录里没有该模型或价格缺失时 cost 留空，单价快照与成本同进同退。
 * 表格结构 / 版面 / OCR 三项外部服务的计费单位未定，其 token 与 cost 一律按未知记录（D34）。
 *
 * <p>写入落点是调用结束后：成功与失败都落一行（形参与调用前检查拦下的失败不落行，那次没有发出请求）。
 * 路由原因与降级来源取自调用上下文：经营由选型的调用写入本次的 route_reason，换模型重试的那次另写
 * fallback_from（记原模型名）；不经选型的调用两列都留空。组装或插入过程出现异常只记日志，不改变调用本身的
 * 返回与异常。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelCallLogRecorder {

    /** 计价基数：目录里的 token 单价按每百万 token 记 */
    private static final BigDecimal TOKENS_PER_MILLION = new BigDecimal("1000000");

    /** 金额精度，与明细表的 decimal(18,6) 对齐 */
    private static final int COST_SCALE = 6;

    /** 用量已知 */
    private static final int USAGE_KNOWN = 0;

    /** 用量未知（tokens 与 cost 记 null） */
    private static final int USAGE_UNKNOWN = 1;

    /** 未命中缓存 */
    private static final int CACHE_MISS = 0;

    private final ModelCallLogPort modelCallLogPort;

    private final ModelCatalogPort modelCatalogPort;

    /**
     * 成功调用落明细：用量取调用上下文（缺时回落到出参），模型与版本取响应回传值。
     *
     * @param vendorCode   厂商码
     * @param modelKey     请求里的模型名
     * @param capability   能力
     * @param trace        调用上下文（尝试次数、用量、延迟）
     * @param model        响应回传的模型（为空即取请求里的模型名）
     * @param modelVersion 响应回传的模型版本（可空）
     * @param resultUsage  出参上的用量（可空，仅作上下文的补充来源）
     */
    public void recordSuccess(String vendorCode, String modelKey, ModelCapability capability, ModelCallTrace trace,
            String model, String modelVersion, ModelUsage resultUsage) {
        ModelUsage usage = NullUtil.isNotNull(trace) && NullUtil.isNotNull(trace.usage())
                ? trace.usage() : resultUsage;
        record(CallStatus.SUCCESS, vendorCode, modelKey, capability, trace, usage, null,
                NullUtil.isNull(model) || model.isBlank() ? modelKey : model, modelVersion);
    }

    /**
     * 失败调用落明细：请求已发出而未成功的调用同样落一行，状态按错误码归入字典取值。
     *
     * @param vendorCode 厂商码
     * @param modelKey   请求里的模型名
     * @param capability 能力
     * @param trace      调用上下文（尝试次数、用量、延迟、错误码）
     * @param errorCode  调用器映射出的错误码（可空）
     */
    public void recordFailure(String vendorCode, String modelKey, ModelCapability capability, ModelCallTrace trace,
            ErrorCode errorCode) {
        ErrorCode failure = NullUtil.isNull(errorCode) && NullUtil.isNotNull(trace) ? trace.errorCode() : errorCode;
        ModelUsage usage = NullUtil.isNull(trace) ? null : trace.usage();
        record(statusOf(failure), vendorCode, modelKey, capability, trace, usage, errorCodeOf(failure), modelKey, null);
    }

    /** 组装一行明细并落库：任何组装或插入异常只记日志，不影响调用结果 */
    private void record(CallStatus status, String vendorCode, String modelKey, ModelCapability capability,
            ModelCallTrace trace, ModelUsage usage, String errorCodeText, String model, String modelVersion) {
        try {
            ModelCallLogEntry entry = new ModelCallLogEntry();
            entry.setCapability(capability);
            entry.setVendor(vendorCode);
            entry.setModel(model);
            entry.setModelVersion(modelVersion);
            entry.setStatus(status);
            entry.setErrorCode(errorCodeText);
            entry.setLatencyMs(latencyOf(trace));
            entry.setRetryCount(NullUtil.isNull(trace) ? 0 : trace.retryCount());
            entry.setCacheHit(CACHE_MISS);
            entry.setRouteReason(NullUtil.isNull(trace) ? null : trace.routeReason());
            entry.setFallbackFrom(NullUtil.isNull(trace) ? null : trace.fallbackFrom());
            fillUsage(entry, capability, vendorCode, modelKey, usage);
            modelCallLogPort.record(entry);
        } catch (RuntimeException e) {
            log.warn("模型调用明细落库失败，厂商：{}，模型：{}，状态：{}", vendorCode, modelKey, status, e);
        }
    }

    /** 用量与成本：未知用量按 null + 标记记录，已知用量按单价快照折算成本 */
    private void fillUsage(ModelCallLogEntry entry, ModelCapability capability, String vendorCode, String modelKey,
            ModelUsage usage) {
        if (usageUnknown(capability, usage)) {
            entry.setUsageUnknown(USAGE_UNKNOWN);
            return;
        }
        entry.setUsageUnknown(USAGE_KNOWN);
        entry.setPromptTokens(tokenOf(usage.getPromptTokens()));
        entry.setCompletionTokens(tokenOf(usage.getCompletionTokens()));
        entry.setTotalTokens(tokenOf(usage.getTotalTokens()));
        ModelDescriptor descriptor = descriptor(vendorCode, modelKey);
        BigDecimal cost = cost(capability, usage, descriptor);
        entry.setCost(cost);
        if (NullUtil.isNotNull(cost)) {
            entry.setUnitPriceIn(descriptor.getPriceInPerM());
            entry.setUnitPriceOut(descriptor.getPriceOutPerM());
            entry.setCurrency(descriptor.getCurrency());
        }
    }

    /**
     * 用量是否未知：表格结构 / 版面 / OCR 三类外部服务的计费单位未定，一律按未知记（D34）；
     * 其余能力下厂商未回 usage，或回了但三项计数全空，也按未知记。
     */
    private boolean usageUnknown(ModelCapability capability, ModelUsage usage) {
        if (capability == ModelCapability.OCR || capability == ModelCapability.LAYOUT
                || capability == ModelCapability.TABLE_STRUCTURE) {
            return true;
        }
        if (NullUtil.isNull(usage)) {
            return true;
        }
        return NullUtil.isNull(usage.getPromptTokens()) && NullUtil.isNull(usage.getCompletionTokens())
                && NullUtil.isNull(usage.getTotalTokens());
    }

    /**
     * 成本：对话调用按「输入 token × 输入单价 + 生成 token × 输出单价」折算（单价按每百万 token 记）。
     * 单价缺失、token 缺项或能力不是对话时返回 null（成本留空，不做估算）；
     * 图像按页与嵌入按千次的口径不在本式内，其成本留空。
     */
    private BigDecimal cost(ModelCapability capability, ModelUsage usage, ModelDescriptor descriptor) {
        if (capability != ModelCapability.CHAT || NullUtil.isNull(descriptor) || NullUtil.isNull(usage)) {
            return null;
        }
        Integer promptTokens = usage.getPromptTokens();
        Integer completionTokens = usage.getCompletionTokens();
        BigDecimal priceIn = descriptor.getPriceInPerM();
        BigDecimal priceOut = descriptor.getPriceOutPerM();
        if (NullUtil.isNull(promptTokens) || NullUtil.isNull(completionTokens) || NullUtil.isNull(priceIn)
                || NullUtil.isNull(priceOut)) {
            return null;
        }
        BigDecimal amount = priceIn.multiply(BigDecimal.valueOf(promptTokens))
                .add(priceOut.multiply(BigDecimal.valueOf(completionTokens)));
        return amount.divide(TOKENS_PER_MILLION, COST_SCALE, RoundingMode.HALF_UP);
    }

    /** 目录价格快照：未收录与读取失败都按"没有价格"处理，成本留空并在日志里留痕 */
    private ModelDescriptor descriptor(String vendorCode, String modelKey) {
        try {
            return modelCatalogPort.findByVendorAndModelKey(vendorCode, modelKey);
        } catch (RuntimeException e) {
            log.warn("模型目录读取失败，厂商：{}，模型：{}，单价快照与成本留空", vendorCode, modelKey, e);
            return null;
        }
    }

    /** 错误码 → 状态：超时与限流各归一档，其余失败归通用失败，成功归成功 */
    private CallStatus statusOf(ErrorCode errorCode) {
        if (NullUtil.isNull(errorCode)) {
            return CallStatus.SUCCESS;
        }
        if (ErrorCode.MODEL_TIMEOUT == errorCode) {
            return CallStatus.TIMEOUT;
        }
        if (ErrorCode.MODEL_RATE_LIMITED == errorCode) {
            return CallStatus.RATE_LIMITED;
        }
        return CallStatus.FAILED;
    }

    /** 错误码落库形态：取枚举名，成功为 null */
    private String errorCodeOf(ErrorCode errorCode) {
        return NullUtil.isNull(errorCode) ? null : errorCode.name();
    }

    /** 用量计数落库形态：{@link Integer} → 明细表的 bigint，未知保持 null */
    private Long tokenOf(Integer tokens) {
        return NullUtil.isNull(tokens) ? null : tokens.longValue();
    }

    /** 延迟落库形态：明细表的 latency_ms 是 int，毫秒耗时按 int 落库，未记录保持 null */
    private Integer latencyOf(ModelCallTrace trace) {
        Long latencyMs = NullUtil.isNull(trace) ? null : trace.latencyMs();
        return NullUtil.isNull(latencyMs) ? null : latencyMs.intValue();
    }
}
