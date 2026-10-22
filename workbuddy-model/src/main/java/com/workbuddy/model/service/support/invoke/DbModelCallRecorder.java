package com.workbuddy.model.service.support.invoke;

import com.workbuddy.common.domain.entity.WbModelCall;
import com.workbuddy.common.domain.model.chat.TokenUsage;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.enums.model.RouteReason;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.model.ModelRef;
import com.workbuddy.model.db.WbModelCallDbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 模型调用明细记录器：一次尝试一行；落库失败只告警，不影响调用主链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbModelCallRecorder {

    private final WbModelCallDbService wbModelCallDbService;

    /**
     * 记录一次尝试。
     *
     * @param requestId  请求追踪 ID
     * @param capability 能力
     * @param ref        模型定位
     * @param attempt    尝试序号（1 起）
     * @param success    是否成功
     * @param errorCode  失败错误码，成功时为空
     * @param latencyMs  耗时（毫秒）
     * @param usage      用量，可空
     * @param reason     路由原因
     */
    public void record(String requestId, ModelCapability capability, ModelRef ref, int attempt,
                       boolean success, ErrorCode errorCode, long latencyMs, TokenUsage usage,
                       RouteReason reason) {
        WbModelCall row = new WbModelCall();
        row.setRequestId(requestId);
        row.setCapability(capability == null ? "UNKNOWN" : capability.name());
        row.setVendorCode(ref.vendorCode());
        row.setModelKey(ref.modelKey());
        row.setRouteReason(reason == null ? RouteReason.OK.name() : reason.name());
        row.setAttempt(attempt);
        row.setStatus(success ? "SUCCESS" : "FAILED");
        row.setErrorCode(success || errorCode == null ? null : errorCode.getCode());
        row.setLatencyMs((int) Math.max(0, latencyMs));
        if (usage != null) {
            row.setPromptTokens(usage.getInputTokens());
            row.setCompletionTokens(usage.getOutputTokens());
        }
        row.setCreateTime(LocalDateTime.now());
        try {
            wbModelCallDbService.save(row);
        } catch (RuntimeException error) {
            log.warn("模型调用明细落库失败, requestId={}, model={}/{}, error={}",
                    requestId, ref.vendorCode(), ref.modelKey(), error.getMessage());
        }
    }
}
