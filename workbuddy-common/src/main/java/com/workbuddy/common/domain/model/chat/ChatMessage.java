package com.workbuddy.common.domain.model.chat;

import lombok.Data;

/**
 * 一条对话消息：角色 + 文本内容。
 */
@Data
public class ChatMessage {

    /** 消息角色 */
    private Role role;

    /** 消息内容 */
    private String content;

    /** 系统提示消息。 */
    public static ChatMessage system(String content) {
        return of(Role.SYSTEM, content);
    }

    /** 用户输入消息。 */
    public static ChatMessage user(String content) {
        return of(Role.USER, content);
    }

    /** 模型回复消息。 */
    public static ChatMessage assistant(String content) {
        return of(Role.ASSISTANT, content);
    }

    private static ChatMessage of(Role role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    /** 消息角色。 */
    public enum Role {

        /** 系统提示 */
        SYSTEM,

        /** 用户输入 */
        USER,

        /** 模型回复 */
        ASSISTANT
    }
}
