package com.workbuddy.common.domain.parse.capability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力使用记录单测。
 *
 * <p>守护工厂的类别常量与 {@code isKind}：类别写错会让管线汇总把模型能力记到别的名下。
 */
class CapabilityUsageTest {

    /**
     * 表格能力：类别固定为 table，模型与版本原样携带。
     *
     * <p>守护 {@code table} 与 {@code isKind}。
     */
    @Test
    void tableUsageShouldCarryKindModelVersion() {
        CapabilityUsage usage = CapabilityUsage.table("table-v3", "3");

        assertEquals(CapabilityUsage.KIND_TABLE, usage.kind());
        assertEquals("table-v3", usage.model());
        assertEquals("3", usage.version());
        assertTrue(usage.isKind(CapabilityUsage.KIND_TABLE));
        assertFalse(usage.isKind(CapabilityUsage.KIND_OCR));
    }
}
