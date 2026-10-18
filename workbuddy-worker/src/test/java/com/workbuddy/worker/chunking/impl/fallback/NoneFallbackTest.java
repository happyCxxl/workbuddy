package com.workbuddy.worker.chunking.impl.fallback;

import com.workbuddy.worker.chunking.strategy.ChunkRouteConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不兜底切片器单测：原样单片 / 空白返回空。
 */
class NoneFallbackTest {

    private final NoneFallback fallback = new NoneFallback();

    @Test
    void textShouldStaySinglePiece() {
        assertEquals(List.of("超长文本不分片"), fallback.slice("超长文本不分片", new ChunkRouteConfig()));
    }

    @Test
    void blankShouldReturnEmpty() {
        assertTrue(fallback.slice("  ", new ChunkRouteConfig()).isEmpty());
    }
}
