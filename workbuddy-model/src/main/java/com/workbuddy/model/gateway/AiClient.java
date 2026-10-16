package com.workbuddy.model.gateway;

import com.workbuddy.common.domain.model.chat.ChatRequest;
import com.workbuddy.common.domain.model.chat.ChatResult;
import com.workbuddy.common.exception.KnowledgeException;

/**
 * 模型能力统一门面：chat 与扩展能力的唯一入口。
 *
 * <p>入参只带模型名，厂商由实现按目录解析（同名多厂商启用时拒绝）；
 * 失败统一抛 {@link KnowledgeException}，码值取 MODEL_* 族。
 *
 * @author cxxl
 */
public interface AiClient {

    /**
     * 对话：消息列表进，回复文本出。
     *
     * @param request 对话请求
     * @return 对话结果
     */
    ChatResult chat(ChatRequest request);
}
