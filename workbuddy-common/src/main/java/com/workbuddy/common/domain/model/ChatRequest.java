package com.workbuddy.common.domain.model;

import lombok.Data;

import java.util.List;

/**
 * 对话模态入参：模型名 + 消息列表 + 输出约束。
 *
 * @author cxxl
 */
@Data
public class ChatRequest {

    /** 厂商侧模型名（wb_model.model_key） */
    private String modelKey;

    /** 消息列表 */
    private List<ChatMessage> messages;

    /** 输出约束 */
    private OutputSpec output;

    /** 契约保留字段（毫秒，可空）；当前超时以实例级配置为准，LC4j 1.22.0 无 per-request 超时入口 */
    private Integer timeoutMs;
}
