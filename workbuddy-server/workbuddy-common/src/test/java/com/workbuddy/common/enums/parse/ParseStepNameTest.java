package com.workbuddy.common.enums.parse;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析子步骤名单测。
 *
 * <p>守护 {@code value()} 的展示名：执行树按步骤名展示，漏名或空名会让步骤显示空白。
 */
class ParseStepNameTest {

    /**
     * 三个步骤都有非空展示名。
     *
     * <p>守护 {@code value}：任一为空即失败。
     */
    @Test
    void valuesShouldBeNonBlank() {
        assertEquals(3, ParseStepName.values().length);
        assertTrue(Arrays.stream(ParseStepName.values())
                .allMatch(step -> !step.value().isBlank()));
        assertEquals("质量检查", ParseStepName.QUALITY_CHECK.value());
    }
}
