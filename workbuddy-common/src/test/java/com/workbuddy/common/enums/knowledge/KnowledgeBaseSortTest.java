package com.workbuddy.common.enums.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 知识库排序单测。
 *
 * <p>守护 {@code of} 的兜底：空白、未知名都回落到 DEFAULT，解析错会让列表排序失序。
 */
class KnowledgeBaseSortTest {

    /**
     * 名称解析：大小写不敏感，未知/空白回落 DEFAULT。
     *
     * <p>守护 {@code of}：任一口径错即失败。
     */
    @Test
    void ofShouldFallbackToDefaultForUnknown() {
        assertEquals(KnowledgeBaseSort.UPDATED, KnowledgeBaseSort.of("updated"));
        assertEquals(KnowledgeBaseSort.NAME, KnowledgeBaseSort.of(" NAME "));
        assertEquals(KnowledgeBaseSort.DEFAULT, KnowledgeBaseSort.of(null));
        assertEquals(KnowledgeBaseSort.DEFAULT, KnowledgeBaseSort.of(" "));
        assertEquals(KnowledgeBaseSort.DEFAULT, KnowledgeBaseSort.of("UNKNOWN"));
    }
}
