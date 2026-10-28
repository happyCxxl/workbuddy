package com.workbuddy.biz.task.runner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片任务执行器页码范围格式化单测：单页/连续段/非连续压缩且不截断（无损等价）。
 */
class ChunkTaskRunnerRangeTest {

    /**
     * 守护 {@code formatPageRangeShouldKeepSingleAndContiguous}：该场景行为改动即失败。
     */
    @Test
    void formatPageRangeShouldKeepSingleAndContiguous() {
        assertNull(ChunkTaskRunner.formatPageRange(null));
        assertNull(ChunkTaskRunner.formatPageRange(List.of()));
        assertEquals("3", ChunkTaskRunner.formatPageRange(List.of(3)));
        assertEquals("1-3", ChunkTaskRunner.formatPageRange(List.of(1, 2, 3)));
    }

    /**
     * 守护 {@code formatPageRangeShouldCompressContiguousRuns}：该场景行为改动即失败。
     */
    @Test
    void formatPageRangeShouldCompressContiguousRuns() {
        // 无序+重复输入：先去重排序再压缩
        assertEquals("1-3,5-6,9", ChunkTaskRunner.formatPageRange(List.of(9, 2, 1, 3, 5, 6, 3)));
        // 全单页（无连续段）：保持逐页枚举
        assertEquals("1,3,5", ChunkTaskRunner.formatPageRange(List.of(1, 3, 5)));
    }

    /**
     * 守护 {@code formatPageRangeShouldNotTruncateLongList}：该场景行为改动即失败。
     */
    @Test
    void formatPageRangeShouldNotTruncateLongList() {
        // 100 个交替页（1,3,...,199）：压缩后仍全量保留（回归：旧列宽 32 放不下也不截断）
        List<Integer> pages = new ArrayList<>();
        for (int p = 1; p <= 199; p += 2) {
            pages.add(p);
        }
        String formatted = Objects.requireNonNull(ChunkTaskRunner.formatPageRange(pages));
        assertEquals(100, formatted.split(",").length);
        assertTrue(formatted.startsWith("1,"));
        assertTrue(formatted.endsWith(",199"));
        assertTrue(formatted.length() > 32);
    }
}
