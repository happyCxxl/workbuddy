package com.workbuddy.common.enums.chunk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片算法目录单测。
 *
 * <p>守护 {@code of} 的（路由，键）二维解析与支持集标记：算法目录是保存校验与
 * 前端置灰的依据，键与路由错位即失败。
 */
class ChunkAlgorithmTest {

    /**
     * 每个算法都能按自己的路由与键解析回来。
     *
     * <p>守护 {@code of}：任一常量的键或路由改错即失败。
     */
    @Test
    void ofShouldResolveEveryRouteAndKey() {
        for (ChunkAlgorithm algorithm : ChunkAlgorithm.values()) {
            assertEquals(algorithm, ChunkAlgorithm.of(algorithm.route(), algorithm.key()),
                    () -> algorithm + " 按自身路由与键解析失败");
        }
    }

    /**
     * 同名键在不同路由解析到不同算法（fixed-window 同时存在正文路与兜底路）。
     *
     * <p>守护 {@code of} 的路由维度：丢掉路由匹配即失败（两路同名键解析串位）。
     */
    @Test
    void ofShouldDistinguishSameKeyAcrossRoutes() {
        assertEquals(ChunkAlgorithm.BODY_FIXED_WINDOW,
                ChunkAlgorithm.of(ChunkRoute.BODY, "fixed-window"));
        assertEquals(ChunkAlgorithm.FALLBACK_FIXED_WINDOW,
                ChunkAlgorithm.of(ChunkRoute.FALLBACK, "fixed-window"));
    }

    /**
     * 路由不符或键未知时返回 null（保存校验据此拒绝）。
     *
     * <p>守护 {@code of} 的未命中口径：把 null 改成抛错即失败。
     */
    @Test
    void ofShouldReturnNullOnRouteMismatchOrUnknownKey() {
        assertNull(ChunkAlgorithm.of(ChunkRoute.TABLE, "paragraph-aggregate"));
        assertNull(ChunkAlgorithm.of(ChunkRoute.BODY, "no-such-key"));
    }

    /**
     * 支持集标记与目录声明一致：未实现的算法不进支持集。
     *
     * <p>守护 {@code supported}：未实现算法标成可选用即失败（前端不置灰、保存不拒绝）。
     */
    @Test
    void supportedShouldMarkUnimplementedAlgorithms() {
        assertTrue(ChunkAlgorithm.BODY_PARAGRAPH_AGGREGATE.supported());
        assertFalse(ChunkAlgorithm.BODY_SEMANTIC.supported());
        assertFalse(ChunkAlgorithm.IMAGE_OCR.supported());
        assertFalse(ChunkAlgorithm.FALLBACK_SEMANTIC.supported());
    }

    /**
     * 表格边界结算仅正文路算法为真，其余路恒为假。
     *
     * <p>守护 {@code flushOnContentBoundary} 的路别语义：表格路算法标真即失败
     * （聚合口径被错误套到表格切片）。
     */
    @Test
    void flushOnContentBoundaryShouldApplyToBodyRouteOnly() {
        assertTrue(ChunkAlgorithm.BODY_PARAGRAPH_AGGREGATE.flushOnContentBoundary());
        assertFalse(ChunkAlgorithm.BODY_TITLE_BOUNDARY.flushOnContentBoundary());
        assertFalse(ChunkAlgorithm.TABLE_ROW_SLICE.flushOnContentBoundary());
        assertFalse(ChunkAlgorithm.FALLBACK_RECURSIVE.flushOnContentBoundary());
    }
}
