package com.workbuddy.model.service.support.invoke;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * 模型调用异常分类：异常 → MODEL_* 错误码，并给出「能否换模型」与「厂商是否不健康」两条判据。
 *
 * <p>两条判据互不推导：结构校验失败可换模型但厂商健康；未知异常不换模型但按不健康计（保守）。
 */
@Component
public class ModelErrorClassifier {

    /** 可换下一个模型重试的错误码 */
    private static final Set<ErrorCode> FALLBACK_CODES = EnumSet.of(
            ErrorCode.MODEL_RATE_LIMITED, ErrorCode.MODEL_TIMEOUT, ErrorCode.MODEL_SERVER_ERROR,
            ErrorCode.MODEL_UNREACHABLE, ErrorCode.MODEL_OUTPUT_INVALID);

    /** 计入厂商不健康的错误码 */
    private static final Set<ErrorCode> UNHEALTHY_CODES = EnumSet.of(
            ErrorCode.MODEL_RATE_LIMITED, ErrorCode.MODEL_TIMEOUT, ErrorCode.MODEL_SERVER_ERROR,
            ErrorCode.MODEL_UNREACHABLE);

    /**
     * 异常 → 知识异常（已映射的原样返回）。
     *
     * @param error 原始异常
     * @return 带 MODEL_* 错误码的知识异常
     */
    public WorkbuddyException translate(Throwable error) {
        String detail = error.getClass().getSimpleName() + ": " + error.getMessage();
        return switch (error) {
            case WorkbuddyException workbuddyException -> workbuddyException;
            case RateLimitException ignored -> new WorkbuddyException(ErrorCode.MODEL_RATE_LIMITED, detail);
            case AuthenticationException ignored -> new WorkbuddyException(ErrorCode.MODEL_AUTH_FAILED, detail);
            case ModelNotFoundException ignored -> new WorkbuddyException(ErrorCode.MODEL_NOT_FOUND, detail);
            // ContentFilteredException 是 InvalidRequestException 的子类，由本分支覆盖
            case InvalidRequestException ignored -> new WorkbuddyException(ErrorCode.MODEL_BAD_REQUEST, detail);
            case TimeoutException ignored -> new WorkbuddyException(ErrorCode.MODEL_TIMEOUT, detail);
            case UnresolvedModelServerException ignored -> new WorkbuddyException(ErrorCode.MODEL_UNREACHABLE, detail);
            default -> new WorkbuddyException(ErrorCode.MODEL_SERVER_ERROR, detail);
        };
    }

    /**
     * 能否换下一个模型再试。
     *
     * @param error 原始异常
     * @return 可换模型为 true
     */
    public boolean canFallback(Throwable error) {
        if (error instanceof WorkbuddyException workbuddyException) {
            return FALLBACK_CODES.contains(workbuddyException.getErrorCode());
        }
        // DNS 不可达是 NonRetriableException 的子类，按覆盖规则归可降级
        if (error instanceof UnresolvedModelServerException) {
            return true;
        }
        return error instanceof RetriableException;
    }

    /**
     * 是否计入厂商不健康（熔断/冷却用）。
     *
     * @param error 原始异常
     * @return 不健康为 true
     */
    public boolean isVendorUnhealthy(Throwable error) {
        if (error instanceof WorkbuddyException workbuddyException) {
            return UNHEALTHY_CODES.contains(workbuddyException.getErrorCode());
        }
        // DNS 不可达按覆盖规则计入不健康
        if (error instanceof UnresolvedModelServerException) {
            return true;
        }
        if (error instanceof RetriableException) {
            return true;
        }
        // 未识别异常按服务端错误计，保守归不健康；其余非可重试异常不算不健康
        return !(error instanceof NonRetriableException);
    }
}
