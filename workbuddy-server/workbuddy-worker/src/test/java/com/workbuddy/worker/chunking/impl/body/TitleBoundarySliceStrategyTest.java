package com.workbuddy.worker.chunking.impl.body;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.impl.fallback.RecursiveLengthFallback;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import com.workbuddy.worker.testkit.PropertiesFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标题边界切片器单测：一节一片 / 超 maxLen 走兜底降级 / 空白不缓冲。
 */
class TitleBoundarySliceStrategyTest {

    private final TitleBoundarySliceStrategy strategy = new TitleBoundarySliceStrategy();

    private SliceContext context(String configJson) {
        SliceContext context = new SliceContext();
        context.setView(new PreprocessView());
        context.setStrategy(new ChunkStrategyParser(PropertiesFixtures.chunk()).parse(configJson));
        context.setById(new HashMap<>());
        context.setTitlePath(List.of("第一章 总则"));
        context.setFallback(new RecursiveLengthFallback());
        return context;
    }

    private ViewElement element(String id, Integer page, String text) {
        ViewElement element = new ViewElement();
        element.setElementId(id);
        element.setType(UnifiedElementType.PARAGRAPH.name());
        element.setPage(page);
        element.setNormalizedText(text);
        return element;
    }

    /**
     * 守护 {@code sectionShouldProduceSingleChunk}：该场景行为改动即失败。
     */
    @Test
    void sectionShouldProduceSingleChunk() {
        SliceContext context = context("{\"routes\":{\"body\":{\"algorithm\":\"title-boundary\"}}}");

        assertTrue(strategy.slice(element("n-1", 1, "第一段"), context).isEmpty());
        assertTrue(strategy.slice(element("n-2", 2, "第二段"), context).isEmpty());

        List<Chunk> chunks = strategy.flush(context);

        assertEquals(1, chunks.size());
        Chunk chunk = chunks.getFirst();
        assertEquals(ChunkContentType.PARAGRAPH.name(), chunk.getContentType());
        assertEquals("第一段\n第二段", chunk.getContent());
        assertEquals(List.of("n-1", "n-2"), chunk.getSourceElementIds());
        assertEquals(List.of(1, 2), chunk.getPageRange());
        assertEquals("第一章 总则", chunk.getTitlePath());
    }

    /**
     * 守护 {@code overlongSectionShouldFallback}：该场景行为改动即失败。
     */
    @Test
    void overlongSectionShouldFallback() {
        SliceContext context = context("{\"routes\":{\"body\":{\"algorithm\":\"title-boundary\","
                + "\"params\":{\"maxLen\":\"100\"}}}}");

        strategy.slice(element("n-1", 1, "a".repeat(1200)), context);

        List<Chunk> chunks = strategy.flush(context);

        // 兜底递归：500/500/200 三片，全部 FALLBACK 且带降级原因
        assertEquals(3, chunks.size());
        assertTrue(chunks.stream().allMatch(c ->
                ChunkContentType.FALLBACK.name().equals(c.getContentType())
                        && "标题边界超长降级".equals(c.getFallbackReason())));
        assertTrue(chunks.stream().allMatch(c -> c.getCharCount() <= 500));
    }

    /**
     * 守护 {@code blankShouldNotBufferAndFlushEmpty}：该场景行为改动即失败。
     */
    @Test
    void blankShouldNotBufferAndFlushEmpty() {
        SliceContext context = context("{\"routes\":{\"body\":{\"algorithm\":\"title-boundary\"}}}");

        assertTrue(strategy.slice(element("n-1", 1, "  "), context).isEmpty());
        assertTrue(strategy.flush(context).isEmpty());
    }
}
