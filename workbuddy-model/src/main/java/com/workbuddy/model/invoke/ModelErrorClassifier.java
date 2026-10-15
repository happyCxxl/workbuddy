package com.workbuddy.model.invoke;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.NullUtil;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.JsonException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Set;

/**
 * 模型调用错误分类：把适配器抛出的异常映射成 {@link ErrorCode}，并按映射结论判定是否可降级。
 * 映射规则集中在本类一处；「是否可降级」只作为本类的判定结论，不落在错误码上。
 *
 * <p>响应体解析失败的落点说明：本版本的 JSON 编解码把解析异常包在普通运行时异常里抛出，
 * 链上出现的是 JSON 库自身的解析异常类型，本类按异常链识别它并归入输出结构错误。
 *
 * @author cxxl
 */
@Component
public class ModelErrorClassifier {

    /** 覆盖规则命中的错误码：网络不可达与输出结构校验失败归可降级 */
    private static final Set<ErrorCode> OVERRIDDEN_CODES =
            Set.of(ErrorCode.MODEL_UNREACHABLE, ErrorCode.MODEL_OUTPUT_INVALID);

    /** 厂商侧不健康的错误码：限流 / 服务端错误 / 超时 / 网络不可达 */
    private static final Set<ErrorCode> UNHEALTHY_CODES =
            Set.of(ErrorCode.MODEL_RATE_LIMITED, ErrorCode.MODEL_SERVER_ERROR, ErrorCode.MODEL_TIMEOUT,
                    ErrorCode.MODEL_UNREACHABLE);

    /**
     * 异常 → 错误码：先按异常类逐类映射，未命中的再按异常链上的解析异常、超时类 IO 异常与网络类异常归类。
     *
     * @param cause 适配器抛出的异常（含各类包装），也可为已带错误码的知识异常
     * @return 对应的错误码
     */
    public ErrorCode classify(Throwable cause) {
        if (NullUtil.isNull(cause)) {
            return ErrorCode.MODEL_SERVER_ERROR;
        }
        if (cause instanceof KnowledgeException knowledgeException) {
            return knowledgeException.getErrorCode();
        }
        if (cause instanceof RateLimitException) {
            return ErrorCode.MODEL_RATE_LIMITED;
        }
        if (cause instanceof AuthenticationException) {
            return ErrorCode.MODEL_AUTH_FAILED;
        }
        if (cause instanceof ContentFilteredException) {
            return ErrorCode.MODEL_BAD_REQUEST;
        }
        if (cause instanceof InvalidRequestException) {
            return ErrorCode.MODEL_BAD_REQUEST;
        }
        if (cause instanceof ModelNotFoundException) {
            return ErrorCode.MODEL_NOT_FOUND;
        }
        if (cause instanceof TimeoutException) {
            return ErrorCode.MODEL_TIMEOUT;
        }
        if (cause instanceof InternalServerException) {
            return ErrorCode.MODEL_SERVER_ERROR;
        }
        if (cause instanceof UnresolvedModelServerException) {
            return ErrorCode.MODEL_UNREACHABLE;
        }
        if (cause instanceof JsonException) {
            return ErrorCode.MODEL_OUTPUT_INVALID;
        }
        return classifyByCauseChain(cause);
    }

    /**
     * 是否可降级：覆盖规则优先（网络不可达、输出结构校验失败归可降级），其余按两个基类判定 ——
     * 可重试异常归可降级，不可重试异常与未识别异常归不可降级。
     *
     * @param cause 适配器抛出的异常，或调用器映射后的知识异常（两者结论一致）
     * @return 可降级为 true
     */
    public boolean canFallback(Throwable cause) {
        if (NullUtil.isNull(cause)) {
            return false;
        }
        if (OVERRIDDEN_CODES.contains(classify(cause))) {
            return true;
        }
        if (chainHas(cause, NonRetriableException.class)) {
            return false;
        }
        return chainHas(cause, RetriableException.class);
    }

    /**
     * 厂商侧是否不健康：限流（429）、服务端错误（5xx）、超时、连接失败与 DNS 不可达归不健康；
     * 鉴权失败、参数错、内容拒绝、模型不存在与输出结构校验失败归健康（那几类指向本次请求或应答，
     * 不指向厂商侧的可用性）。
     *
     * <p>口径独立于 {@link #canFallback(Throwable)}：输出结构校验失败可降级而不算厂商不健康，
     * 两者按各自的判定面取值，请求体里的异常与调用器映射后的知识异常同样适用。
     *
     * <p>判定分两条：异常链上带不可重试基类的，只有网络不可达归不健康，其余（鉴权、参数、内容拒绝、
     * 模型不存在，以及裸的不可重试异常）归健康；链上没有该基类的按 {@link #classify(Throwable)}
     * 给出的码值判，未识别的异常落在服务端错误这一类，按不健康记。
     *
     * @param cause 适配器抛出的异常，或调用器映射后的知识异常
     * @return 厂商侧不健康为 true
     */
    public boolean isVendorUnhealthy(Throwable cause) {
        if (NullUtil.isNull(cause)) {
            return false;
        }
        ErrorCode code = classify(cause);
        if (chainHas(cause, NonRetriableException.class)) {
            return code == ErrorCode.MODEL_UNREACHABLE;
        }
        return UNHEALTHY_CODES.contains(code);
    }

    /**
     * 异常链归类：空指针归输出结构错误、解析异常归输出结构错误、超时类 IO 异常归超时、
     * 其余 IO 与地址解析异常归不可达，都未命中即按服务端错误处理。
     * 空指针先于其余归类：响应缺字段（例如向量应答缺 data）在下游解引用处就是这一种，
     * 归成输出结构错误，既不退化成服务端错误，也随覆盖规则进入可降级集合。
     * 解析异常先于 IO 归类，两代 JSON 库的解析异常都继承 IO 异常。
     */
    private ErrorCode classifyByCauseChain(Throwable cause) {
        if (chainHas(cause, NullPointerException.class)) {
            return ErrorCode.MODEL_OUTPUT_INVALID;
        }
        if (chainHas(cause, JacksonException.class) || chainHas(cause, JsonProcessingException.class)) {
            return ErrorCode.MODEL_OUTPUT_INVALID;
        }
        if (chainHas(cause, HttpTimeoutException.class) || chainHas(cause, SocketTimeoutException.class)) {
            return ErrorCode.MODEL_TIMEOUT;
        }
        if (chainHas(cause, UnresolvedAddressException.class) || chainHas(cause, UnknownHostException.class)
                || chainHas(cause, IOException.class)) {
            return ErrorCode.MODEL_UNREACHABLE;
        }
        return ErrorCode.MODEL_SERVER_ERROR;
    }

    /** 异常链上是否有该类型的节点（含入参自身） */
    private boolean chainHas(Throwable cause, Class<? extends Throwable> type) {
        Throwable node = cause;
        while (NullUtil.isNotNull(node)) {
            if (type.isInstance(node)) {
                return true;
            }
            node = node.getCause() == node ? null : node.getCause();
        }
        return false;
    }
}
