package com.workbuddy.common.enums.chunk;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片流水线参数键单测。
 *
 * <p>守护 {@code key()} 的快照键名：参数键是策略快照契约，改名会让历史策略读不到参数。
 */
class PipelineKeyTest {

    /**
     * 键名逐项固定且互不重复。
     *
     * <p>守护 {@code key}：任一键名改动或重复即失败。
     */
    @Test
    void keysShouldBeStableAndUnique() {
        assertEquals("titlePathMaxLevel", PipelineKey.TITLE_PATH_MAX_LEVEL.key());
        assertEquals("parentChild", PipelineKey.PARENT_CHILD.key());
        assertEquals("tableInBodyFlow", PipelineKey.TABLE_IN_BODY_FLOW.key());
        assertEquals("minMergeLen", PipelineKey.MIN_MERGE_LEN.key());
        assertEquals("structureOverlap", PipelineKey.STRUCTURE_OVERLAP.key());
        assertEquals("titleInContent", PipelineKey.TITLE_IN_CONTENT.key());

        Set<String> keys = Arrays.stream(PipelineKey.values())
                .map(PipelineKey::key).collect(Collectors.toSet());
        assertEquals(PipelineKey.values().length, keys.size());
        assertTrue(keys.stream().noneMatch(String::isBlank));
    }
}
