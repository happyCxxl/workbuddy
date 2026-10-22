package com.workbuddy.model.service.support.invoke;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.InternalServerException;
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
        if (error instanceof WorkbuddyException workbuddyException) {
            return workbuddyException;
        }
        String detail = error.getClass().getSimpleName() + ": " + error.getMessage();
        if (error instanceof RateLimitException) {
            return new WorkbuddyException(ErrorCode.MODEL_RATE_LIMITED, detail);
        }
        if (error instanceof AuthenticationException) {
            return new WorkbuddyException(ErrorCode.MODEL_AUTH_FAILED, detail);
        }
        if (error instanceof ModelNotFoundException) {
            return new WorkbuddyException(ErrorCode.MODEL_NOT_FOUND, detail);
        }
        if (error instanceof InvalidRequestException || error instanceof ContentFilteredException) {
            return new WorkbuddyException(ErrorCode.MODEL_BAD_REQUEST, detail);
        }
        if (error instanceof TimeoutException) {
            return new WorkbuddyException(ErrorCode.MODEL_TIMEOUT, detail);
        }
        if (error instanceof InternalServerException) {
            return new WorkbuddyException(ErrorCode.MODEL_SERVER_ERROR, detail);
        }
        if (error instanceof UnresolvedModelServerException) {
            return new WorkbuddyException(ErrorCode.MODEL_UNREACHABLE, detail);
        }
        return new WorkbuddyException(ErrorCode.MODEL_SERVER_ERROR, detail);
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
        if (error instanceof RateLimitException || error instanceof TimeoutException
                || error instanceof InternalServerException || error instanceof UnresolvedModelServerException) {
            return true;
        }
        if (error instanceof RetriableException) {
            return true;
        }
        return false;
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
        if (error instanceof RateLimitException || error instanceof TimeoutException
                || error instanceof InternalServerException || error instanceof UnresolvedModelServerException) {
            return true;
        }
        if (error instanceof RetriableException) {
            return true;
        }
        if (error instanceof AuthenticationException || error instanceof InvalidRequestException
                || error instanceof ContentFilteredException || error instanceof ModelNotFoundException
                || error instanceof NonRetriableException) {
            return false;
        }
        return true;
    }
}
