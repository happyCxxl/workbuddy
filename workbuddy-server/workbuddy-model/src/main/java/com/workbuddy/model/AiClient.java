package com.workbuddy.model;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.chat.ChatRequest;
import com.workbuddy.common.domain.model.chat.ChatResult;
import com.workbuddy.common.domain.model.vision.VisionRequest;
import com.workbuddy.common.exception.WorkbuddyException;

/**
 * 模型能力统一门面：chat / embed / vision 的唯一入口。
 *
 * <p>入参不指定具体模型：厂商与模型由目录候选与路由决定（{@code embed} 的固定模型名除外）；
 * 失败统一抛 {@link WorkbuddyException}，码值取 MODEL_* 族。
 */
public interface AiClient {

    /**
     * 对话：消息列表进，回复文本出。
     *
     * @param request 对话请求
     * @return 对话结果
     */
    ChatResult chat(ChatRequest request);

    /**
     * 向量编码：一批文本进，向量列表出。
     *
     * @param request 向量化请求（model 为精确过滤条件）
     * @return 向量结果
     */
    EmbeddingResult embed(EmbeddingRequest request);

    /**
     * 视觉/OCR：图片进，文本或 JSON 出（结果结构与 chat 共用）。
     *
     * @param request 视觉请求
     * @return 视觉结果
     */
    ChatResult vision(VisionRequest request);
}
