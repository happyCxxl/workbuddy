package com.workbuddy.common.domain.model.chat;

import lombok.Data;

/**
 * 对话采样参数：字段可空，空值由适配层走厂商缺省。
 */
@Data
public class ChatOptions {

    /** 采样温度 */
    private Double temperature;

    /** 回复长度上限（token 数） */
    private Integer maxOutputTokens;
}
