package com.workbuddy.model.adapter;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.AuthType;
import com.workbuddy.common.domain.model.ChatRequest;
import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;

import java.util.Set;

/**
 * 模型适配器 SPI：声明支持的鉴权方式与三个模态方法，厂商身份与协议身份不落在本接口上。
 * 失败抛 KnowledgeException，错误码取 ErrorCode 的模型调用分区。
 *
 * @author cxxl
 */
public interface ModelAdapter {

    /**
     * 该适配器支持的鉴权方式，启动期用于校验厂商配置的 auth-type。
     *
     * @return 支持的鉴权方式集合
     */
    Set<AuthType> supportedAuthTypes();

    /**
     * 对话模态：首参厂商码，模型名在 ChatRequest.modelKey。
     *
     * @param vendorCode 厂商码
     * @param request    对话入参（模型/消息/输出约束/超时）
     * @return 原始文本 + 通用树 + 用量 + 模型标识
     */
    GenerationResult chat(String vendorCode, ChatRequest request);

    /**
     * 向量编码模态：首参厂商码，模型名在 EmbeddingRequest.model；默认实现抛「模型未支持该模态」。
     *
     * @param vendorCode 厂商码
     * @param request    向量入参（模型/文本/超时/批大小/requestId）
     * @return 向量列表 + 维度 + 模型回显 + requestId
     */
    default EmbeddingResult embed(String vendorCode, EmbeddingRequest request) {
        throw new KnowledgeException(ErrorCode.MODEL_MODALITY_UNSUPPORTED, "模态：embed，厂商：" + vendorCode);
    }

    /**
     * 视觉模态：首参厂商码，模型名在 VisionRequest.modelKey；默认实现抛「模型未支持该模态」。
     *
     * @param vendorCode 厂商码
     * @param request    视觉入参（模型/图像/区域/提示词/输出约束/超时）
     * @return 原始文本 + 通用树 + 用量 + 模型标识
     */
    default GenerationResult vision(String vendorCode, VisionRequest request) {
        throw new KnowledgeException(ErrorCode.MODEL_MODALITY_UNSUPPORTED, "模态：vision，厂商：" + vendorCode);
    }
}
