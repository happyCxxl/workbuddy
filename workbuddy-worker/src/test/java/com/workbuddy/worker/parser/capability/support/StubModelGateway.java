package com.workbuddy.worker.parser.capability.support;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.model.gateway.ModelGatewayPort;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台网关桩（测试用）：记录视觉调用收到的入参与能力，按设定给出出参；向量侧不参与用例。
 * 已收到的调用次数按「按能力选型」与「按厂商码直调」两个口分别计数。
 *
 * @author cxxl
 */
public class StubModelGateway implements ModelGatewayPort {

    /** 按能力选型收下的入参与能力（按调用顺序） */
    private final List<VisionRequest> capabilityRequests = new ArrayList<>();

    /** 按能力选型收下的能力（按调用顺序） */
    private final List<ModelCapability> capabilityCalls = new ArrayList<>();

    /** 设定给视觉调用的出参 */
    private GenerationResult response = new GenerationResult();

    /** 设定给视觉调用的异常；非空即抛出 */
    private RuntimeException failure;

    /**
     * 设定出参。
     *
     * @param content 出参文本
     * @param model   出参模型名
     * @param version 出参模型版本
     * @return 本对象（链式设置）
     */
    public StubModelGateway respondWith(String content, String model, String version) {
        GenerationResult result = new GenerationResult();
        result.setContent(content);
        result.setModel(model);
        result.setModelVersion(version);
        this.response = result;
        return this;
    }

    /**
     * 设定调用异常。
     *
     * @param errorCode 错误码
     * @return 本对象（链式设置）
     */
    public StubModelGateway failWith(ErrorCode errorCode) {
        this.failure = new KnowledgeException(errorCode, "用例构造的失败");
        return this;
    }

    /** 最近一次按能力选型收到的入参；未调用过返回 null */
    public VisionRequest lastRequest() {
        return capabilityRequests.isEmpty() ? null : capabilityRequests.getLast();
    }

    /** 已收到的按能力选型调用次数 */
    public int visionCount() {
        return capabilityCalls.size();
    }

    /** 已收到调用里的能力（按调用顺序） */
    public List<ModelCapability> capabilities() {
        return capabilityCalls;
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) {
        throw new KnowledgeException(ErrorCode.MODEL_MODALITY_UNSUPPORTED, "用例不覆盖向量模态");
    }

    @Override
    public GenerationResult visionByCapability(ModelCapability capability, VisionRequest request) {
        capabilityRequests.add(request);
        capabilityCalls.add(capability);
        if (failure != null) {
            throw failure;
        }
        return response;
    }
}
