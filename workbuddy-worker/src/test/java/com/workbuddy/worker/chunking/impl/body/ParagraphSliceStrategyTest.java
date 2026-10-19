package com.workbuddy.worker.chunking.impl.body;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.impl.fallback.RecursiveLengthFallback;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.testkit.StrategyFixtures;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正文切片器单测：段落聚合（缓冲在 SliceContext，跨元素合并/flush 结算）、超长递归降级、titlePath。
 */
class ParagraphSliceStrategyTest {

    private final ParagraphSliceStrategy strategy = new ParagraphSliceStrategy();

    private SliceContext context() {
        SliceContext context = new SliceContext();
        context.setView(new PreprocessView());
        ChunkProperties props = new ChunkProperties();
        context.setStrategy(StrategyFixtures.chunkDefaults(props));
        context.setById(new HashMap<>());
        context.setTitlePath(List.of("第一章 总则"));
        context.setFallback(new RecursiveLengthFallback());
        return context;
    }

    private ViewElement element(String id, Integer page, String text) {
        ViewElement element = new ViewElement();
        element.setElementId(id);
        element.setPage(page);
        element.setNormalizedText(text);
        return element;
    }

    /**
     * 守护 {@code smallParagraphsShouldMergeAndFlushOnBoundary}：该场景行为改动即失败。
     */
    @Test
    void smallParagraphsShouldMergeAndFlushOnBoundary() {
        SliceContext context = context();

        // 两个小段落累积，不到上限不成片
        assertTrue(strategy.slice(element("n-1", 1, "第一段内容"), context).isEmpty());
        assertTrue(strategy.slice(element("n-2", 1, "第二段内容"), context).isEmpty());

        // flush 结算为一合并片
        List<Chunk> chunks = strategy.flush(context);

        assertEquals(1, chunks.size());
        Chunk chunk = chunks.getFirst();
        assertEquals("第一段内容\n第二段内容", chunk.getContent());
        assertEquals(ChunkContentType.PARAGRAPH.name(), chunk.getContentType());
        assertEquals(List.of("n-1", "n-2"), chunk.getSourceElementIds());
        assertEquals(List.of(1), chunk.getPageRange());
        assertEquals("第一章 总则", chunk.getTitlePath());
        assertTrue(context.getBodyBuffer().isEmpty());
    }

    /**
     * 守护 {@code overlongElementShouldFallbackRecursively}：该场景行为改动即失败。
     */
    @Test
    void overlongElementShouldFallbackRecursively() {
        SliceContext context = context();
        // 先累积一个小段落
        strategy.slice(element("n-1", 1, "前置段落"), context);

        List<Chunk> chunks = strategy.slice(element("n-2", 2, "a".repeat(1200)), context);

        // 前置段落先结算为正文片 + 超长元素硬切 3 片兜底（500/500/300）
        assertEquals(4, chunks.size());
        assertEquals(ChunkContentType.PARAGRAPH.name(), chunks.getFirst().getContentType());
        assertEquals("前置段落", chunks.getFirst().getContent());
        assertTrue(chunks.stream().skip(1).allMatch(c ->
                ChunkContentType.FALLBACK.name().equals(c.getContentType())
                        && "超长段落递归降级".equals(c.getFallbackReason())));
        assertTrue(chunks.stream().skip(1).allMatch(c -> c.getCharCount() <= 500));
    }

    /**
     * 守护 {@code flushOnEmptyBufferShouldReturnEmpty}：该场景行为改动即失败。
     */
    @Test
    void flushOnEmptyBufferShouldReturnEmpty() {
        assertTrue(strategy.flush(context()).isEmpty());
    }
}
