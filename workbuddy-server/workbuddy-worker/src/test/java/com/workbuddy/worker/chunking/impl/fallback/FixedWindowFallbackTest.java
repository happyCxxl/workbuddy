package com.workbuddy.worker.chunking.impl.fallback;

import com.workbuddy.worker.chunking.strategy.ChunkRouteConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定窗口兜底切片器单测：窗口长度 + 重叠。
 */
class FixedWindowFallbackTest {

    private final FixedWindowFallback fallback = new FixedWindowFallback();

    private ChunkRouteConfig config(int len, int overlap) {
        ChunkRouteConfig config = new ChunkRouteConfig();
        config.setAlgorithm("fixed-window");
        Map<String, String> params = new HashMap<>();
        params.put("len", String.valueOf(len));
        params.put("overlap", String.valueOf(overlap));
        config.setParams(params);
        return config;
    }

    /**
     * 守护 {@code windowShouldSliceWithOverlap}：该场景行为改动即失败。
     */
    @Test
    void windowShouldSliceWithOverlap() {
        List<String> pieces = fallback.slice("x".repeat(250), config(100, 20));

        // 步长 80：0-100 / 80-180 / 160-250（末片 90）
        assertEquals(3, pieces.size());
        assertEquals(List.of(100, 100, 90), pieces.stream().map(String::length).toList());
    }

    /**
     * 守护 {@code shortTextShouldStaySinglePiece}：该场景行为改动即失败。
     */
    @Test
    void shortTextShouldStaySinglePiece() {
        assertEquals(List.of("短文本"), fallback.slice("短文本", config(500, 50)));
    }

    /**
     * 守护 {@code blankShouldReturnEmpty}：该场景行为改动即失败。
     */
    @Test
    void blankShouldReturnEmpty() {
        assertTrue(fallback.slice("  ", config(500, 50)).isEmpty());
    }
}
