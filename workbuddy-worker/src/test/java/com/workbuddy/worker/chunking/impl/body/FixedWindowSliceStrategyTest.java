package com.workbuddy.worker.chunking.impl.body;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.impl.fallback.RecursiveLengthFallback;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定窗口切片器单测：flush 点窗口切（len + overlap）/ 短文本单片 / 溯源字段。
 *
 * @author cxxl
 */
class FixedWindowSliceStrategyTest {

    private final FixedWindowSliceStrategy strategy = new FixedWindowSliceStrategy();

    private SliceContext context(String configJson) {
        SliceContext context = new SliceContext();
        context.setView(new PreprocessView());
        context.setStrategy(new ChunkStrategyParser(new ChunkProperties()).parse(configJson));
        context.setById(new HashMap<>());
        context.setTitlePath(List.of("第一章 总则"));
        context.setFallback(new RecursiveLengthFallback());
        return context;
    }

    private ViewElement element(String text) {
        ViewElement element = new ViewElement();
        element.setElementId("n-1");
        element.setType(UnifiedElementType.PARAGRAPH.name());
        element.setPage(1);
        element.setNormalizedText(text);
        return element;
    }

    @Test
    void flushShouldWindowSliceWithOverlap() {
        SliceContext context = context("{\"routes\":{\"body\":{\"algorithm\":\"fixed-window\",\"params\":{\"len\":\"100\",\"overlap\":\"20\"}}}}");

        assertTrue(strategy.slice(element("x".repeat(250)), context).isEmpty());

        List<Chunk> chunks = strategy.flush(context);

        // 步长 80：0-100 / 80-180 / 160-250（末片 90）
        assertEquals(3, chunks.size());
        assertEquals(List.of(100, 100, 90), chunks.stream().map(Chunk::getCharCount).toList());
        assertTrue(chunks.stream().allMatch(c -> ChunkContentType.PARAGRAPH.name().equals(c.getContentType())));
        assertTrue(chunks.stream().allMatch(c -> c.getContent().startsWith("xxx")));
        assertEquals(List.of("n-1"), chunks.getFirst().getSourceElementIds());
        assertEquals(List.of(1), chunks.getFirst().getPageRange());
        assertEquals("第一章 总则", chunks.getFirst().getTitlePath());
    }

    @Test
    void shortTextShouldProduceSingleChunk() {
        SliceContext context = context("{\"routes\":{\"body\":{\"algorithm\":\"fixed-window\"}}}");

        strategy.slice(element("短文本"), context);

        List<Chunk> chunks = strategy.flush(context);

        assertEquals(1, chunks.size());
        assertEquals("短文本", chunks.getFirst().getContent());
    }
}
