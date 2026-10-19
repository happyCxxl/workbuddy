package com.workbuddy.common.enums.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 审计对象类型单测。
 *
 * <p>守护 {@code of} 的键解析与中文名：对象类型是审计落库的维度之一。
 */
class AuditObjectTypeTest {

    /**
     * 每个对象类型都能按键解析回来，且有中文名。
     *
     * <p>守护 {@code of} 与 {@code label}：键改错或说明缺失即失败。
     */
    @Test
    void ofShouldResolveEveryKeyWithLabel() {
        for (AuditObjectType type : AuditObjectType.values()) {
            assertEquals(type, AuditObjectType.of(type.key()), () -> type + " 按键解析失败");
            assertFalse(type.label().isBlank(), () -> type + " 缺中文名");
        }
        assertNull(AuditObjectType.of("no-such-type"));
    }
}
