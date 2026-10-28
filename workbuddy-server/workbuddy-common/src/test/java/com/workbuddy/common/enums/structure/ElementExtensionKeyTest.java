package com.workbuddy.common.enums.structure;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 元素扩展字段键单测。
 *
 * <p>守护 {@code key()} 的下发键名：快照里的键是前后端契约，改名会让历史产物读不到扩展信息。
 */
class ElementExtensionKeyTest {

    /**
     * 键名逐项固定且互不重复。
     *
     * <p>守护 {@code key}：任一键名改动或重复即失败。
     */
    @Test
    void keysShouldBeStableAndUnique() {
        assertEquals("source", ElementExtensionKey.SOURCE.key());
        assertEquals("provider", ElementExtensionKey.PROVIDER.key());
        assertEquals("style", ElementExtensionKey.STYLE.key());
        assertEquals("sheetName", ElementExtensionKey.SHEET_NAME.key());
        assertEquals("needsOcr", ElementExtensionKey.NEEDS_OCR.key());
        assertEquals("cutAtPageBottom", ElementExtensionKey.CUT_AT_PAGE_BOTTOM.key());
        assertEquals("headerRepeated", ElementExtensionKey.HEADER_REPEATED.key());

        Set<String> keys = Arrays.stream(ElementExtensionKey.values())
                .map(ElementExtensionKey::key).collect(Collectors.toSet());
        assertEquals(ElementExtensionKey.values().length, keys.size());
        assertTrue(keys.stream().noneMatch(String::isBlank));
    }
}
