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
 * 句子聚合切片器单测：跨元素句级聚合 / 目标长度结算 / 超长句兜底降级。
 */
class SentenceAggregateSliceStrategyTest {

    private final SentenceAggregateSliceStrategy strategy = new SentenceAggregateSliceStrategy();

    private SliceContext context(String configJson) {
        SliceContext context = new SliceContext();
        context.setView(new PreprocessView());
        context.setStrategy(new ChunkStrategyParser(new ChunkProperties()).parse(configJson));
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

    @Test
    void sentencesShouldAggregateAcrossElements() {
        SliceContext context = context("{\"routes\":{\"body\":{\"algorithm\":\"sentence-aggregate\"}}}");

        assertTrue(strategy.slice(element("n-1", 1, "第一句。第二句。"), context).isEmpty());
        assertTrue(strategy.slice(element("n-2", 1, "第三句。"), context).isEmpty());

        List<Chunk> chunks = strategy.flush(context);

        assertEquals(1, chunks.size());
        Chunk chunk = chunks.getFirst();
        assertEquals(ChunkContentType.PARAGRAPH.name(), chunk.getContentType());
        assertEquals("第一句。\n第二句。\n第三句。", chunk.getContent());
        assertEquals(List.of("n-1", "n-2"), chunk.getSourceElementIds());
    }

    @Test
    void bufferShouldSettleAtTargetMaxLen() {
        SliceContext context = context("{\"routes\":{\"body\":{\"algorithm\":\"sentence-aggregate\",\"params\":{\"targetMaxLen\":\"10\"}}}}");

        List<Chunk> chunks = strategy.slice(element("n-1", 1, "第一句。第二句。第三句。"), context);

        // 三句累计超过 10 字符即结算成一片
        assertEquals(1, chunks.size());
        assertEquals("第一句。\n第二句。\n第三句。", chunks.getFirst().getContent());
        assertTrue(context.getBodyBuffer().isEmpty());
    }

    @Test
    void overlongSentenceShouldFallbackRecursively() {
        SliceContext context = context("{\"routes\":{\"body\":{\"algorithm\":\"sentence-aggregate\",\"params\":{\"softMaxLen\":\"100\"}}}}");

        List<Chunk> chunks = strategy.slice(element("n-1", 2, "a".repeat(1200) + "。"), context);

        // 超长单句（1201 字符 > 100）→ 兜底 500/500/201 三片
        assertEquals(3, chunks.size());
        assertTrue(chunks.stream().allMatch(c ->
                ChunkContentType.FALLBACK.name().equals(c.getContentType())
                        && "超长句子递归降级".equals(c.getFallbackReason())));
        assertTrue(chunks.stream().allMatch(c -> c.getCharCount() <= 500));
    }
}
