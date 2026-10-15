package com.workbuddy.worker.chunking.impl.body;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.impl.fallback.RecursiveLengthFallback;
import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构混合切片器单测：复用段落聚合行为（算法键不同）；聚合结算 / 超长降级。
 *
 * @author cxxl
 */
class StructureHybridSliceStrategyTest {

    private final StructureHybridSliceStrategy strategy = new StructureHybridSliceStrategy();

    private SliceContext context() {
        SliceContext context = new SliceContext();
        context.setView(new PreprocessView());
        context.setStrategy(new ChunkStrategyParser(new ChunkProperties())
                .parse("{\"routes\":{\"body\":{\"algorithm\":\"structure-hybrid\"}}}"));
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
    void algorithmIdentityShouldBeStructureHybrid() {
        assertEquals(ChunkAlgorithm.BODY_STRUCTURE_HYBRID, strategy.algorithm());
    }

    @Test
    void smallElementsShouldAggregateOnFlush() {
        SliceContext context = context();

        assertTrue(strategy.slice(element("n-1", 1, "第一段内容"), context).isEmpty());
        assertTrue(strategy.slice(element("n-2", 1, "第二段内容"), context).isEmpty());

        List<Chunk> chunks = strategy.flush(context);

        assertEquals(1, chunks.size());
        assertEquals(ChunkContentType.PARAGRAPH.name(), chunks.getFirst().getContentType());
        assertEquals("第一段内容\n第二段内容", chunks.getFirst().getContent());
        assertEquals(List.of("n-1", "n-2"), chunks.getFirst().getSourceElementIds());
    }

    @Test
    void overlongElementShouldFallbackRecursively() {
        SliceContext context = context();

        List<Chunk> chunks = strategy.slice(element("n-1", 2, "a".repeat(1200)), context);

        assertEquals(3, chunks.size());
        assertTrue(chunks.stream().allMatch(c ->
                ChunkContentType.FALLBACK.name().equals(c.getContentType())
                        && "超长段落递归降级".equals(c.getFallbackReason())));
        assertTrue(chunks.stream().allMatch(c -> c.getCharCount() <= 500));
    }
}
