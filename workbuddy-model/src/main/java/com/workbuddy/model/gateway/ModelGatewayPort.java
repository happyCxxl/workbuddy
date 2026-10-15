package com.workbuddy.model.gateway;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.ChatRequest;
import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;

/**
 * 模型能力网关端口：三个模态方法 —— chat 对话、embed 向量编码、vision 视觉。
 * 模型名一律在请求体（ChatRequest.modelKey / VisionRequest.modelKey / EmbeddingRequest.model），厂商由参数给出；
 * 视觉模态另有一条按能力选型的入口，厂商码由候选本身给出。
 * 失败抛 KnowledgeException，错误码取 ErrorCode 的模型调用分区。
 *
 * @author cxxl
 */
public interface ModelGatewayPort {

    /**
     * 批量向量编码：texts 原样编码（顺序即结果顺序）。
     *
     * @param request 本批输入（模型/文本/超时/批大小/requestId）
     * @return 向量列表（与输入一一对应）+ 维度 + 模型回显
     */
    EmbeddingResult embed(EmbeddingRequest request);

    /**
     * 对话模态：首参厂商码，模型名在 ChatRequest.modelKey。
     *
     * @param vendorCode 厂商码
     * @param request    对话入参（模型/消息/输出约束/超时）
     * @return 原始文本 + 通用树 + 用量 + 模型标识
     */
    default GenerationResult chat(String vendorCode, ChatRequest request) {
        throw new KnowledgeException(ErrorCode.MODEL_MODALITY_UNSUPPORTED, "模态：chat，厂商：" + vendorCode);
    }

    /**
     * 视觉模态：首参厂商码，模型名在 VisionRequest.modelKey。
     *
     * @param vendorCode 厂商码
     * @param request    视觉入参（模型/图像/区域/提示词/输出约束/超时）
     * @return 原始文本 + 通用树 + 用量 + 模型标识
     */
    default GenerationResult vision(String vendorCode, VisionRequest request) {
        throw new KnowledgeException(ErrorCode.MODEL_MODALITY_UNSUPPORTED, "模态：vision，厂商：" + vendorCode);
    }

    /**
     * 视觉模态（按能力自动选型）：候选集按形参给出的能力取，厂商码由本次选中的候选给出，
     * 模型名写进 VisionRequest.modelKey。
     *
     * @param capability 能力（OCR / LAYOUT / TABLE_STRUCTURE）
     * @param request    视觉入参（模型名由选型结果覆盖）
     * @return 原始文本 + 通用树 + 用量 + 模型标识
     */
    default GenerationResult visionByCapability(ModelCapability capability, VisionRequest request) {
        throw new KnowledgeException(ErrorCode.MODEL_MODALITY_UNSUPPORTED, "模态：vision，能力：" + capability);
    }

    /**
     * 按模型名解析厂商码：目录里唯一命中即取它的厂商码，目录未收录时回落到唯一已配置厂商。
     *
     * @param modelKey 模型名
     * @return 厂商码
     */
    default String resolveVendorCode(String modelKey) {
        throw new KnowledgeException(ErrorCode.MODEL_MODALITY_UNSUPPORTED, "厂商解析，模型：" + modelKey);
    }
}
