package com.workbuddy.common.domain.parse;

import com.workbuddy.common.enums.parse.ElementType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 解析元素单测。
 *
 * <p>守护两个工厂：带 ID 的原样保留、不带的置空；类型统一存枚举名。
 */
class ParseElementTest {

    /**
     * 带 ID：ID 原样、类型为枚举名。
     *
     * <p>守护 {@code ParseElement.of}。
     */
    @Test
    void ofWithIdShouldCarryIdAndType() {
        ParseElement element = ParseElement.of("e-1", ElementType.PARAGRAPH);

        assertEquals("e-1", element.getId());
        assertEquals(ElementType.PARAGRAPH.name(), element.getType());
    }

    /**
     * 不带 ID：ID 为空，类型仍固定。
     *
     * <p>守护 {@code ParseElement.of}：ID 由产出路径统一分配。
     */
    @Test
    void ofWithoutIdShouldLeaveIdNull() {
        ParseElement element = ParseElement.of(ElementType.TABLE);

        assertNull(element.getId());
        assertEquals(ElementType.TABLE.name(), element.getType());
    }
}
