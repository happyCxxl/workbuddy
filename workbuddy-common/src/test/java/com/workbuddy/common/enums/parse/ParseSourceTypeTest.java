package com.workbuddy.common.enums.parse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 解析来源类型单测。
 *
 * <p>守护 {@code ofValue} 的值解析：来源类型随产物落库，值错位即溯源断链。
 */
class ParseSourceTypeTest {

    /**
     * 每个来源都能按值解析回来。
     *
     * <p>守护 {@code ofValue}：值改错即失败（产物来源解析不了）。
     */
    @Test
    void ofValueShouldResolveEverySource() {
        for (ParseSourceType type : ParseSourceType.values()) {
            assertEquals(type, ParseSourceType.ofValue(type.value()), () -> type + " 按值解析失败");
        }
        assertNull(ParseSourceType.ofValue("no-such-source"));
    }
}
