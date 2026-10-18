package com.workbuddy.common.domain.model.chat;

import lombok.Data;

/**
 * 对话 token 用量。
 */
@Data
public class TokenUsage {

    /** 输入 token 数 */
    private Integer inputTokens;

    /** 输出 token 数 */
    private Integer outputTokens;

    /** 合计 token 数 */
    private Integer totalTokens;
}
