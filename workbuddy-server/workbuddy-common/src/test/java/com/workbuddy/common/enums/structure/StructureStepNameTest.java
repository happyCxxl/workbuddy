package com.workbuddy.common.enums.structure;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组装子步骤名单测。
 *
 * <p>守护 {@code value()} 的展示名：执行树按步骤名展示，漏名或空名会让步骤显示空白。
 */
class StructureStepNameTest {

    /**
     * 五个步骤都有非空展示名。
     *
     * <p>守护 {@code value}：任一为空即失败。
     */
    @Test
    void valuesShouldBeNonBlank() {
        assertEquals(5, StructureStepName.values().length);
        assertTrue(Arrays.stream(StructureStepName.values())
                .allMatch(step -> !step.value().isBlank()));
        assertEquals("溯源校验", StructureStepName.VERIFY_PROVENANCE.value());
    }
}
