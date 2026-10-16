package com.workbuddy.common.domain.model.chat;

import lombok.Data;

import java.util.List;

/**
 * 对话请求：模型名 + 消息列表 + 可空参数组。
 *
 * @author cxxl
 */
@Data
public class ChatRequest {

    /** 模型名（目录收录口径） */
    private String model;

    /** 消息列表（首轮即 system + user） */
    private List<ChatMessage> messages;

    /** 采样参数组，空 = 整组走厂商缺省 */
    private ChatOptions options;
}
