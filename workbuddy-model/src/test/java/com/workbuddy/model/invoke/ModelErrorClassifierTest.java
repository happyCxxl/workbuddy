package com.workbuddy.model.invoke;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.JsonException;
import dev.langchain4j.exception.JsonReadException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误分类单测：逐类异常的映射结果与可降级结论，含覆盖规则、两个基类与被包装的异常链。
 *
 * @author cxxl
 */
class ModelErrorClassifierTest {

    private final ModelErrorClassifier classifier = new ModelErrorClassifier();

    @Test
    void rateLimitedShouldBeRateLimitedAndAllowFallback() {
        RateLimitException cause = new RateLimitException("429");

        assertEquals(ErrorCode.MODEL_RATE_LIMITED, classifier.classify(cause));
        assertTrue(classifier.canFallback(cause));
    }

    @Test
    void authFailedShouldBeAuthFailedAndBlockFallback() {
        AuthenticationException cause = new AuthenticationException("401");

        assertEquals(ErrorCode.MODEL_AUTH_FAILED, classifier.classify(cause));
        assertFalse(classifier.canFallback(cause));
    }

    @Test
    void invalidRequestShouldBeBadRequestAndBlockFallback() {
        InvalidRequestException cause = new InvalidRequestException("400");

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, classifier.classify(cause));
        assertFalse(classifier.canFallback(cause));
    }

    @Test
    void contentFilteredShouldBeBadRequestAndBlockFallback() {
        ContentFilteredException cause = new ContentFilteredException("DataInspectionFailed");

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, classifier.classify(cause));
        assertFalse(classifier.canFallback(cause));
    }

    @Test
    void modelNotFoundShouldBeNotFoundAndBlockFallback() {
        ModelNotFoundException cause = new ModelNotFoundException("404");

        assertEquals(ErrorCode.MODEL_NOT_FOUND, classifier.classify(cause));
        assertFalse(classifier.canFallback(cause));
    }

    @Test
    void timeoutShouldBeTimeoutAndAllowFallback() {
        TimeoutException cause = new TimeoutException("408");

        assertEquals(ErrorCode.MODEL_TIMEOUT, classifier.classify(cause));
        assertTrue(classifier.canFallback(cause));
    }

    @Test
    void internalServerErrorShouldBeServerErrorAndAllowFallback() {
        InternalServerException cause = new InternalServerException("500");

        assertEquals(ErrorCode.MODEL_SERVER_ERROR, classifier.classify(cause));
        assertTrue(classifier.canFallback(cause));
    }

    @Test
    void unresolvedModelServerShouldBeUnreachableAndAllowFallback() {
        UnresolvedModelServerException cause = new UnresolvedModelServerException("dns");

        assertEquals(ErrorCode.MODEL_UNREACHABLE, classifier.classify(cause));
        assertTrue(classifier.canFallback(cause));
    }

    @Test
    void jsonReadFailuresShouldBeOutputInvalidAndAllowFallback() {
        JsonException jsonException = new JsonException("bad json");
        JsonReadException jsonReadException = new JsonReadException(new IllegalArgumentException("bad json"));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, classifier.classify(jsonException));
        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, classifier.classify(jsonReadException));
        assertTrue(classifier.canFallback(jsonException));
        assertTrue(classifier.canFallback(jsonReadException));
    }

    @Test
    void wrappedJsonParseFailureShouldBeOutputInvalidAndAllowFallback() {
        JsonProcessingException parseFailure = assertThrows(JsonProcessingException.class,
                () -> new ObjectMapper().readValue("not-json", Map.class));
        RuntimeException wrapped = new RuntimeException(parseFailure);

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, classifier.classify(wrapped));
        assertTrue(classifier.canFallback(wrapped));
    }

    @Test
    void nullPointerFromMissingResponseFieldsShouldBeOutputInvalidAndAllowFallback() {
        NullPointerException missingData = new NullPointerException();
        RuntimeException wrapped = new RuntimeException("向量应答缺 data", missingData);

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, classifier.classify(missingData));
        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, classifier.classify(wrapped));
        assertEquals(40469, classifier.classify(wrapped).getCode());
        assertTrue(classifier.canFallback(missingData));
        assertTrue(classifier.canFallback(wrapped));
    }

    @Test
    void networkFailuresShouldBeUnreachableAndAllowFallback() {
        RuntimeException refused = new RuntimeException(new ConnectException("Connection refused"));
        UnknownHostException unknownHost = new UnknownHostException("nope.invalid");

        assertEquals(ErrorCode.MODEL_UNREACHABLE, classifier.classify(refused));
        assertEquals(ErrorCode.MODEL_UNREACHABLE, classifier.classify(unknownHost));
        assertTrue(classifier.canFallback(refused));
        assertTrue(classifier.canFallback(unknownHost));
    }

    @Test
    void ioTimeoutShouldBeTimeoutAndFollowBaseClassRule() {
        HttpTimeoutException ioTimeout = new HttpTimeoutException("request timed out");

        assertEquals(ErrorCode.MODEL_TIMEOUT, classifier.classify(ioTimeout));
        // 可降级依据两个基类：该 IO 异常自身不可降级，实际链路上它会先被转成可重试的超时异常
        assertFalse(classifier.canFallback(ioTimeout));
        assertTrue(classifier.canFallback(new TimeoutException(ioTimeout)));
    }

    @Test
    void unknownErrorShouldBeServerErrorAndBlockFallback() {
        RuntimeException cause = new RuntimeException("boom");

        assertEquals(ErrorCode.MODEL_SERVER_ERROR, classifier.classify(cause));
        assertFalse(classifier.canFallback(cause));
        assertEquals(ErrorCode.MODEL_SERVER_ERROR, classifier.classify(null));
        assertFalse(classifier.canFallback(null));
    }

    @Test
    void retriableBaseClassShouldAllowFallbackWithoutSpecificCode() {
        RetriableException retriable = new RetriableException("transient");
        NonRetriableException nonRetriable = new NonRetriableException("permanent");

        assertEquals(ErrorCode.MODEL_SERVER_ERROR, classifier.classify(retriable));
        assertTrue(classifier.canFallback(retriable));
        assertEquals(ErrorCode.MODEL_SERVER_ERROR, classifier.classify(nonRetriable));
        assertFalse(classifier.canFallback(nonRetriable));
    }

    @Test
    void knowledgeExceptionShouldKeepItsCodeAndFollowOverrides() {
        KnowledgeException outputInvalid = new KnowledgeException(ErrorCode.MODEL_OUTPUT_INVALID, "结构不符");
        KnowledgeException unsupported = new KnowledgeException(ErrorCode.MODEL_MODALITY_UNSUPPORTED, "模态：vision");

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, classifier.classify(outputInvalid));
        assertTrue(classifier.canFallback(outputInvalid));
        assertEquals(ErrorCode.MODEL_MODALITY_UNSUPPORTED, classifier.classify(unsupported));
        assertFalse(classifier.canFallback(unsupported));
    }

    @Test
    void vendorUnhealthyShouldCoverRateLimitServerErrorTimeoutAndUnreachable() {
        assertTrue(classifier.isVendorUnhealthy(new RateLimitException("429")));
        assertTrue(classifier.isVendorUnhealthy(new InternalServerException("500")));
        assertTrue(classifier.isVendorUnhealthy(new TimeoutException("408")));
        assertTrue(classifier.isVendorUnhealthy(new UnresolvedModelServerException("dns")));
        assertTrue(classifier.isVendorUnhealthy(new RuntimeException(new ConnectException("Connection refused"))));
        assertTrue(classifier.isVendorUnhealthy(new UnknownHostException("nope.invalid")));
        assertTrue(classifier.isVendorUnhealthy(new RuntimeException(new HttpTimeoutException("request timed out"))));
        assertTrue(classifier.isVendorUnhealthy(new RetriableException("transient")));
    }

    @Test
    void vendorHealthyShouldCoverAuthBadRequestContentFilteredNotFoundAndOutputInvalid() {
        assertFalse(classifier.isVendorUnhealthy(new AuthenticationException("401")));
        assertFalse(classifier.isVendorUnhealthy(new InvalidRequestException("400")));
        assertFalse(classifier.isVendorUnhealthy(new ContentFilteredException("DataInspectionFailed")));
        assertFalse(classifier.isVendorUnhealthy(new ModelNotFoundException("404")));
        assertFalse(classifier.isVendorUnhealthy(new KnowledgeException(ErrorCode.MODEL_OUTPUT_INVALID, "结构不符")));
        assertFalse(classifier.isVendorUnhealthy(new KnowledgeException(ErrorCode.MODEL_MODALITY_UNSUPPORTED, "模态")));
        assertFalse(classifier.isVendorUnhealthy(new RuntimeException(new NullPointerException("缺 data"))));
        assertFalse(classifier.isVendorUnhealthy(null));
    }

    /**
     * 未识别的异常落在服务端错误这一类，按厂商侧不健康记：那一次失败无法归到请求或应答一侧，
     * 保守地计一次失败，避免厂商侧真出问题时熔断器不收口。
     */
    @Test
    void unrecognizedFailureShouldCountAsVendorUnhealthy() {
        RuntimeException unknown = new RuntimeException("boom");

        assertEquals(ErrorCode.MODEL_SERVER_ERROR, classifier.classify(unknown));
        assertFalse(classifier.canFallback(unknown));
        assertTrue(classifier.isVendorUnhealthy(unknown));
    }

    /**
     * 两条口径的差异点之一：输出结构校验失败可降级，而不算厂商侧不健康。
     * 该失败指向本次应答的结构，换模型重试有意义，熔断器的失败率不该被它推高。
     */
    @Test
    void outputInvalidShouldAllowFallbackWithoutCountingAsVendorUnhealthy() {
        KnowledgeException cause = new KnowledgeException(ErrorCode.MODEL_OUTPUT_INVALID, "输出结构不符");

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, classifier.classify(cause));
        assertTrue(classifier.canFallback(cause));
        assertFalse(classifier.isVendorUnhealthy(cause));
    }

    /**
     * 两条口径的差异点之二：鉴权失败既不可降级也不算厂商侧不健康；
     * 裸的不可重试异常在类型上是「不可重试」、码值上是服务端错误，类型先判，按厂商侧健康记。
     */
    @Test
    void authFailedShouldBlockFallbackAndStayVendorHealthy() {
        AuthenticationException auth = new AuthenticationException("401");
        Throwable bareNonRetriable = new NonRetriableException("服务端拒绝的参数");

        assertFalse(classifier.canFallback(auth));
        assertFalse(classifier.isVendorUnhealthy(auth));
        assertEquals(ErrorCode.MODEL_SERVER_ERROR, classifier.classify(bareNonRetriable));
        assertFalse(classifier.canFallback(bareNonRetriable));
        assertFalse(classifier.isVendorUnhealthy(bareNonRetriable));
    }
}
