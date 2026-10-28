package com.workbuddy.model.service.support.invoke;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异常分类单测：错误码映射、可降级与不健康两条判据。
 */
class ModelErrorClassifierTest {

    private final ModelErrorClassifier classifier = new ModelErrorClassifier();

    /** 限流：40465，可降级且不健康（{@code rateLimitShouldFallbackAndUnhealthy}）。 */
    @Test
    void rateLimitShouldFallbackAndUnhealthy() {
        RateLimitException error = new RateLimitException("429");

        assertEquals(ErrorCode.MODEL_RATE_LIMITED, classifier.translate(error).getErrorCode());
        assertTrue(classifier.canFallback(error));
        assertTrue(classifier.isVendorUnhealthy(error));
    }

    /** 鉴权失败：40462，不降级也不计不健康（{@code authenticationShouldNotFallback}）。 */
    @Test
    void authenticationShouldNotFallback() {
        AuthenticationException error = new AuthenticationException("401");

        assertEquals(ErrorCode.MODEL_AUTH_FAILED, classifier.translate(error).getErrorCode());
        assertFalse(classifier.canFallback(error));
        assertFalse(classifier.isVendorUnhealthy(error));
    }

    /** 参数/内容拒绝：40463，不降级（{@code invalidRequestShouldNotFallback}）。 */
    @Test
    void invalidRequestShouldNotFallback() {
        assertFalse(classifier.canFallback(new InvalidRequestException("400")));
        assertEquals(ErrorCode.MODEL_BAD_REQUEST,
                classifier.translate(new InvalidRequestException("400")).getErrorCode());
    }

    /** 模型不存在：40464（{@code modelNotFoundShouldMapToNotFound}）。 */
    @Test
    void modelNotFoundShouldMapToNotFound() {
        ModelNotFoundException error = new ModelNotFoundException("no model");

        assertEquals(ErrorCode.MODEL_NOT_FOUND, classifier.translate(error).getErrorCode());
        assertFalse(classifier.canFallback(error));
    }

    /** 超时与服务端错误：可降级且不健康（{@code timeoutAndServerErrorShouldFallback}）。 */
    @Test
    void timeoutAndServerErrorShouldFallback() {
        TimeoutException timeout = new TimeoutException("timeout");
        InternalServerException server = new InternalServerException("500");

        assertEquals(ErrorCode.MODEL_TIMEOUT, classifier.translate(timeout).getErrorCode());
        assertEquals(ErrorCode.MODEL_SERVER_ERROR, classifier.translate(server).getErrorCode());
        assertTrue(classifier.canFallback(timeout));
        assertTrue(classifier.canFallback(server));
        assertTrue(classifier.isVendorUnhealthy(timeout));
        assertTrue(classifier.isVendorUnhealthy(server));
    }

    /** 网络不可达：40468（{@code unresolvedServerShouldMapToUnreachable}）。 */
    @Test
    void unresolvedServerShouldMapToUnreachable() {
        UnresolvedModelServerException error = new UnresolvedModelServerException("dns");

        assertEquals(ErrorCode.MODEL_UNREACHABLE, classifier.translate(error).getErrorCode());
        assertTrue(classifier.canFallback(error));
        assertTrue(classifier.isVendorUnhealthy(error));
    }

    /** 已分类的结构校验失败：可降级但不计不健康（{@code outputInvalidShouldFallbackButHealthy}）。 */
    @Test
    void outputInvalidShouldFallbackButHealthy() {
        WorkbuddyException error = new WorkbuddyException(ErrorCode.MODEL_OUTPUT_INVALID, "schema");

        assertTrue(classifier.canFallback(error));
        assertFalse(classifier.isVendorUnhealthy(error));
        assertEquals(error, classifier.translate(error));
    }

    /** 未知异常：按服务端错误收口，不换模型但按不健康计（{@code unknownShouldBeConservative}）。 */
    @Test
    void unknownShouldBeConservative() {
        RuntimeException error = new RuntimeException("boom");

        assertEquals(ErrorCode.MODEL_SERVER_ERROR, classifier.translate(error).getErrorCode());
        assertFalse(classifier.canFallback(error));
        assertTrue(classifier.isVendorUnhealthy(error));
    }
}
