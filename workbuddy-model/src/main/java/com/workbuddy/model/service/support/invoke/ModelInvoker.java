package com.workbuddy.model.service.support.invoke;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.domain.model.chat.ChatRequest;
import com.workbuddy.common.domain.model.chat.ChatResult;
import com.workbuddy.common.domain.model.chat.TokenUsage;
import com.workbuddy.common.domain.model.vision.VisionRequest;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.enums.model.RouteReason;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.ModelAdapter;
import com.workbuddy.model.ModelRef;
import com.workbuddy.model.config.ModelRouteProperties;
import com.workbuddy.model.service.support.capacity.ModelCapacityGuard;
import com.workbuddy.model.service.support.capacity.ModelCooldownStore;
import com.workbuddy.model.service.support.route.ModelCircuitBreakers;
import com.workbuddy.model.service.support.route.ModelRouter;
import com.workbuddy.model.service.support.route.RouteStateStore;
import io.github.resilience4j.retry.MaxRetriesExceededException;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 模型调用器：一次调用的固定流程——选模型 → 熔断/容量许可 → 调适配器 → 记录延迟与明细；
 * 失败按错误分类决定是否换下一个候选，最多尝试 `max-attempts` 次。
 *
 * <p>chat 与 embed 共用同一套「选路 + 许可 + 降级 + 明细」逻辑，差异只在传入的适配器回调。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelInvoker {

    private final ModelAdapter modelAdapter;

    private final ModelRouter modelRouter;

    private final ModelCapacityGuard modelCapacityGuard;

    private final ModelCircuitBreakers modelCircuitBreakers;

    private final ModelCooldownStore modelCooldownStore;

    private final OutputSchemaValidator outputSchemaValidator;

    private final ModelErrorClassifier modelErrorClassifier;

    private final ModelRouteProperties modelRouteProperties;

    private final RouteStateStore routeStateStore;

    private final DbModelCallRecorder modelCallRecorder;

    /**
     * 对话调用。
     *
     * @param request 对话请求
     * @return 对话结果
     */
    public ChatResult chat(ChatRequest request) {
        outputSchemaValidator.checkBeforeCall(request.getOutput());
        ModelCapability capability = NullUtil.isNull(request.getCapability())
                ? ModelCapability.CHAT : request.getCapability();
        return invoke(capability, null, model -> {
            ChatResult result = modelAdapter.chat(model, request);
            result.setJson(outputSchemaValidator.validate(request.getOutput(), result.getContent()));
            return result;
        }, ChatResult::getTokenUsage);
    }

    /**
     * 向量编码：请求里的模型名作为精确过滤条件。
     *
     * @param request 向量化请求
     * @return 向量结果
     */
    public EmbeddingResult embed(EmbeddingRequest request) {
        return invoke(ModelCapability.EMBEDDING, request.getModel(),
                model -> modelAdapter.embed(model, request), result -> null);
    }

    /**
     * 视觉/OCR 调用：图片字节或引用二选一，复用对话的选路、限流、降级与结构校验。
     *
     * @param request 视觉请求
     * @return 视觉结果
     */
    public ChatResult vision(VisionRequest request) {
        validateVision(request);
        outputSchemaValidator.checkBeforeCall(request.getOutput());
        return invoke(ModelCapability.OCR, null, model -> {
            ChatResult result = modelAdapter.vision(model, request);
            result.setJson(outputSchemaValidator.validate(request.getOutput(), result.getContent()));
            return result;
        }, ChatResult::getTokenUsage);
    }

    /**
     * 视觉入参校验：字节与引用二选一，字节必须带 MIME
     */
    private void validateVision(VisionRequest request) {
        ThrowUtil.throwIf(request.hasBytes() && StrUtil.isBlank(request.getMimeType()), ErrorCode.MODEL_BAD_REQUEST, "视觉请求缺少图片 MIME 类型");
        ThrowUtil.throwIf(!request.hasBytes() && StrUtil.isBlank(request.getImageRef()), ErrorCode.MODEL_BAD_REQUEST, "视觉请求缺少图片字节或引用");
    }

    /**
     * 通用调用模板：每次尝试重新选候选，熔断/容量不足或可降级失败时换下一个。
     *
     * @param capability     所需能力
     * @param modelKeyFilter 精确模型名过滤，可空
     * @param call           实际调用回调
     * @param usageOf        从结果取用量（无用量返回 null）
     * @param <T>            结果类型
     * @return 调用结果
     */
    private <T> T invoke(ModelCapability capability, String modelKeyFilter,
                         Function<ModelDescriptor, T> call, Function<T, TokenUsage> usageOf) {
        String requestId = UUID.randomUUID().toString();
        int maxAttempts = Math.max(1, modelRouteProperties.getMaxAttempts());
        Set<ModelRef> tried = new HashSet<>();
        AtomicReference<WorkbuddyException> lastError = new AtomicReference<>();
        AtomicInteger attemptCounter = new AtomicInteger();
        Retry retry = Retry.of("model-invoke", RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ZERO)
                .retryOnException(this::shouldRetry)
                .build());
        try {
            return retry.executeSupplier(() -> {
                ModelDescriptor model = modelRouter.select(capability, modelKeyFilter, tried);
                if (NullUtil.isNull(model)) {
                    WorkbuddyException previous = lastError.get();
                    if (NullUtil.isNotNull(previous)) {
                        throw previous;
                    }
                    throw new WorkbuddyException(ErrorCode.MODEL_NOT_FOUND,
                            "无可用模型：capability=" + capability + filterDetail(modelKeyFilter));
                }
                int attempt = attemptCounter.incrementAndGet();
                ModelRef ref = new ModelRef(model.getVendorCode(), model.getModelKey());
                tried.add(ref);
                if (!modelCircuitBreakers.tryAcquire(ref)) {
                    WorkbuddyException unavailable = new WorkbuddyException(ErrorCode.MODEL_UNAVAILABLE,
                            "模型熔断中：" + ref.vendorCode() + "/" + ref.modelKey());
                    lastError.set(unavailable);
                    throw unavailable;
                }
                if (!modelCapacityGuard.tryAcquire(ref, model.getRpmLimit(), model.getTpmLimit())) {
                    modelCircuitBreakers.releasePermission(ref);
                    WorkbuddyException unavailable = new WorkbuddyException(ErrorCode.MODEL_UNAVAILABLE,
                            "模型容量不足：" + ref.vendorCode() + "/" + ref.modelKey());
                    lastError.set(unavailable);
                    throw unavailable;
                }
                long startedAt = System.currentTimeMillis();
                try {
                    T result = call.apply(model);
                    long latencyMs = System.currentTimeMillis() - startedAt;
                    modelCircuitBreakers.onSuccess(ref, latencyMs);
                    routeStateStore.recordLatency(ref, latencyMs);
                    modelCallRecorder.record(requestId, capability, ref, attempt, true, null,
                            latencyMs, usageOf.apply(result), reason(attempt - 1));
                    return result;
                } catch (RuntimeException error) {
                    long latencyMs = System.currentTimeMillis() - startedAt;
                    WorkbuddyException translated = modelErrorClassifier.translate(error);
                    if (modelErrorClassifier.isVendorUnhealthy(error)) {
                        modelCircuitBreakers.onFailure(ref, latencyMs, error);
                        if (translated.getErrorCode() == ErrorCode.MODEL_RATE_LIMITED) {
                            modelCooldownStore.markCooldown(ref,
                                    Duration.ofSeconds(modelRouteProperties.getCooldownSeconds()));
                        }
                    } else {
                        modelCircuitBreakers.onSuccess(ref, latencyMs);
                    }
                    lastError.set(translated);
                    modelCallRecorder.record(requestId, capability, ref, attempt, false,
                            translated.getErrorCode(), latencyMs, null, reason(attempt - 1));
                    log.warn("模型调用失败，换下一个候选: {}/{}, error={}",
                            ref.vendorCode(), ref.modelKey(), translated.getMessage());
                    throw translated;
                }
            });
        } catch (MaxRetriesExceededException exhausted) {
            WorkbuddyException last = lastError.get();
            if (NullUtil.isNotNull(last)) {
                throw last;
            }
            throw new WorkbuddyException(ErrorCode.MODEL_SERVER_ERROR, "模型调用重试耗尽");
        }
    }

    /** Retry 判定：容量/熔断跳过与可降级错误才换下一个，其余直接停 */
    private boolean shouldRetry(Throwable error) {
        if (error instanceof WorkbuddyException workbuddyException) {
            return workbuddyException.getErrorCode() == ErrorCode.MODEL_UNAVAILABLE
                    || modelErrorClassifier.canFallback(workbuddyException);
        }
        return modelErrorClassifier.canFallback(error);
    }

    /**
     * 首次尝试记常规，后续记降级而来
     */
    private RouteReason reason(int attempt) {
        return attempt == 0 ? RouteReason.OK : RouteReason.FALLBACK_FROM;
    }

    /**
     * 无候选时的可读原因
     */
    private String filterDetail(String modelKeyFilter) {
        return NullUtil.isNull(modelKeyFilter) ? "" : "，model=" + modelKeyFilter;
    }
}
