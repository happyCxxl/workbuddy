package com.workbuddy.common.domain.model.chat;

import com.workbuddy.common.enums.model.ModelCapability;
import lombok.Data;

import java.util.List;

/**
 * 对话请求：消息列表 + 可空参数组；模型由模块在注册表候选集中按能力选定，请求不指定模型。
 */
@Data
public class ChatRequest {

    /** 消息列表（首轮即 system + user） */
    private List<ChatMessage> messages;

    /** 采样参数组，空 = 整组走厂商缺省 */
    private ChatOptions options;

    /** 所需能力，空按 CHAT 处理 */
    private ModelCapability capability = ModelCapability.CHAT;
}
