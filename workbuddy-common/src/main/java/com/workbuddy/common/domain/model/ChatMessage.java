package com.workbuddy.common.domain.model;

import lombok.Data;

/**
 * 一条消息：角色 + 文本内容。
 *
 * @author cxxl
 */
@Data
public class ChatMessage {

    /** 消息角色 */
    private MessageRole role;

    /** 消息文本 */
    private String content;
}
