package com.workbuddy.common.domain.model.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 对话消息单测。
 *
 * <p>守护三个角色工厂：system/user/assistant 分别对应固定角色且内容原样下发；
 * 角色写错会让模型把系统提示当成用户输入。
 */
class ChatMessageTest {

    /**
     * 三个工厂的角色与内容逐项固定。
     *
     * <p>守护 {@code system}/{@code user}/{@code assistant}。
     */
    @Test
    void factoriesShouldCarryFixedRoles() {
        ChatMessage system = ChatMessage.system("规则");
        assertEquals(ChatMessage.Role.SYSTEM, system.getRole());
        assertEquals("规则", system.getContent());

        ChatMessage user = ChatMessage.user("问题");
        assertEquals(ChatMessage.Role.USER, user.getRole());
        assertEquals("问题", user.getContent());

        ChatMessage assistant = ChatMessage.assistant("回答");
        assertEquals(ChatMessage.Role.ASSISTANT, assistant.getRole());
        assertEquals("回答", assistant.getContent());
    }
}
