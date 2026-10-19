package com.workbuddy.worker.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合纯函数单测：名次融合/同名去重取最高分/k 参数/空通道容忍。
 */
class RrfFusionTest {

    /**
     * 守护 {@code fuseShouldRankByReciprocalRankAndMergeDuplicates}：该场景行为改动即失败。
     */
    @Test
    void fuseShouldRankByReciprocalRankAndMergeDuplicates() {
        // 全文：a(1) b(2)；向量：b(1) c(2)
        List<RrfFusion.FusedHit> fused = RrfFusion.fuse(
                List.of(List.of("a", "b"), List.of("b", "c")), 60);

        assertEquals(List.of("b", "a", "c"), fused.stream().map(RrfFusion.FusedHit::chunkId).toList());
        assertEquals(1.0 / 61 + 1.0 / 62, fused.getFirst().score(), 1e-9); // b 双通道名次合并
        assertEquals(1.0 / 61, fused.get(1).score(), 1e-9);
        assertEquals(1.0 / 62, fused.get(2).score(), 1e-9);
    }

    /**
     * 守护 {@code fuseShouldTolerateNullChannel}：该场景行为改动即失败。
     */
    @Test
    void fuseShouldTolerateNullChannel() {
        List<RrfFusion.FusedHit> fused = RrfFusion.fuse(java.util.Arrays.asList(null, List.of("a")), 60);

        assertEquals(List.of("a"), fused.stream().map(RrfFusion.FusedHit::chunkId).toList());
    }

    /**
     * 守护 {@code fuseShouldReturnEmptyForNoHits}：该场景行为改动即失败。
     */
    @Test
    void fuseShouldReturnEmptyForNoHits() {
        assertTrue(RrfFusion.fuse(List.of(), 60).isEmpty());
    }
}
