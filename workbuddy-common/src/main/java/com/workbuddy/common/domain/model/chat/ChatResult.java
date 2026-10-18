package com.workbuddy.common.domain.model.chat;

import com.workbuddy.common.enums.model.FinishReason;
import lombok.Data;

/**
 * 对话结果：回复文本 + 模型名回显 + 停止原因与 token 用量。
 */
@Data
public class ChatResult {

    /** 模型回复文本 */
    private String content;

    /** 模型名回显 */
    private String model;

    /** 停止原因 */
    private FinishReason finishReason;

    /** token 用量 */
    private TokenUsage tokenUsage;

    /** 厂商响应 id，可空 */
    private String id;
}
