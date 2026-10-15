package com.workbuddy.model.support;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.AuthType;
import com.workbuddy.common.domain.model.ChatRequest;
import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.ModelUsage;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.model.adapter.ModelAdapter;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可编排的假适配器（测试用）：按模型名决定这次调用成功还是失败，并按错误码给出可降级或不可降级的异常。
 * 三个模态共用同一份失败表与延迟表；调用过的模型名按顺序留存，供用例断言「先调了谁、后调了谁」。
 *
 * @author cxxl
 */
public class RecordingAdapter implements ModelAdapter {

    /** 成功应答的向量维度 */
    private static final int DIMENSION = 2;

    /** 模型名 → 失败错误码；不在表里的模型一律成功 */
    private final Map<String, ErrorCode> failures = new LinkedHashMap<>();

    /** 模型名 → 成功应答的延迟（毫秒） */
    private final Map<String, Long> delays = new LinkedHashMap<>();

    /** 调用过的模型名（按调用顺序） */
    private final List<String> called = new ArrayList<>();

    /** 成功应答是否带回用量：关掉即模拟厂商未回 usage */
    private boolean usageReported = true;

    /**
     * 关掉成功应答的用量：模拟厂商未回 usage，调用链按请求文本长度粗估占用。
     *
     * @return 本对象（链式设置）
     */
    public RecordingAdapter withoutUsage() {
        this.usageReported = false;
        return this;
    }

    /**
     * 登记某个模型失败。
     *
     * @param modelKey  模型名
     * @param errorCode 失败错误码
     * @return 本对象（链式登记）
     */
    public RecordingAdapter failing(String modelKey, ErrorCode errorCode) {
        failures.put(modelKey, errorCode);
        return this;
    }

    /**
     * 登记某个模型成功应答的延迟。
     *
     * @param modelKey 模型名
     * @param delayMs  延迟（毫秒）
     * @return 本对象（链式登记）
     */
    public RecordingAdapter delaying(String modelKey, long delayMs) {
        delays.put(modelKey, delayMs);
        return this;
    }

    /** 调用过的模型名（按调用顺序） */
    public List<String> called() {
        return called;
    }

    @Override
    public Set<AuthType> supportedAuthTypes() {
        return Set.of(AuthType.BEARER);
    }

    @Override
    public GenerationResult chat(String vendorCode, ChatRequest request) {
        String modelKey = request.getModelKey();
        called.add(modelKey);
        delayOf(modelKey);
        ErrorCode failure = failures.get(modelKey);
        if (failure != null) {
            throw exceptionOf(failure, modelKey);
        }
        GenerationResult result = new GenerationResult();
        result.setContent("pong-" + modelKey);
        result.setModel(modelKey);
        if (usageReported) {
            ModelUsage usage = new ModelUsage();
            usage.setPromptTokens(10);
            usage.setCompletionTokens(5);
            usage.setTotalTokens(15);
            result.setUsage(usage);
        }
        return result;
    }

    @Override
    public EmbeddingResult embed(String vendorCode, EmbeddingRequest request) {
        String modelKey = request.getModel();
        called.add(modelKey);
        delayOf(modelKey);
        ErrorCode failure = failures.get(modelKey);
        if (failure != null) {
            throw exceptionOf(failure, modelKey);
        }
        EmbeddingResult result = new EmbeddingResult();
        result.setEmbeddings(List.of(List.of(0.1F, 0.2F), List.of(0.3F, 0.4F)));
        result.setDimension(DIMENSION);
        result.setModel(modelKey);
        result.setRequestId(request.getRequestId());
        return result;
    }

    @Override
    public GenerationResult vision(String vendorCode, VisionRequest request) {
        String modelKey = request.getModelKey();
        called.add(modelKey);
        delayOf(modelKey);
        ErrorCode failure = failures.get(modelKey);
        if (failure != null) {
            throw exceptionOf(failure, modelKey);
        }
        GenerationResult result = new GenerationResult();
        result.setContent("{\"lines\":[]}");
        result.setModel(modelKey);
        if (usageReported) {
            ModelUsage usage = new ModelUsage();
            usage.setPromptTokens(20);
            usage.setCompletionTokens(5);
            usage.setTotalTokens(25);
            result.setUsage(usage);
        }
        return result;
    }

    /** 失败异常：按错误码挑一个能带出可降级结论的异常类型 */
    private RuntimeException exceptionOf(ErrorCode errorCode, String modelKey) {
        String detail = "厂商：dashscope，模型：" + modelKey + "，原因：用例构造的失败";
        if (errorCode == ErrorCode.MODEL_BAD_REQUEST) {
            return new InvalidRequestException(detail);
        }
        if (errorCode != ErrorCode.MODEL_SERVER_ERROR) {
            return new KnowledgeException(errorCode, detail);
        }
        return new InternalServerException(detail);
    }

    /** 按登记的延迟等待：让调用耗时成为真实可读的延迟样本 */
    private void delayOf(String modelKey) {
        Long delayMs = delays.get(modelKey);
        if (delayMs == null || delayMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
