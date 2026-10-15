package com.workbuddy.worker.chunking.impl.body;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.impl.fallback.RecursiveLengthFallback;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 正文超长降级单测：标题边界与段落聚合共用同一降级入口（FALLBACK 片 + fallbackReason + 片长受兜底参数约束）。
 *
 * @author cxxl
 */
class BodyDegradeTest {

    /** 标题边界：一节 100 字符封顶，兜底 50 字符一片、不重叠 */
    private static final String TITLE_BOUNDARY_CONFIG = "{\"routes\":{"
            + "\"body\":{\"algorithm\":\"title-boundary\",\"params\":{\"maxLen\":\"100\"}},"
            + "\"fallback\":{\"algorithm\":\"recursive-length\",\"params\":{\"len\":\"50\",\"overlap\":\"0\"}}}}";

    /** 段落聚合：目标 100 / 软上限 50，兜底 30 字符一片、不重叠 */
    private static final String PARAGRAPH_CONFIG = "{\"routes\":{"
            + "\"body\":{\"algorithm\":\"paragraph-aggregate\",\"params\":{\"targetMaxLen\":\"100\",\"softMaxLen\":\"50\"}},"
            + "\"fallback\":{\"algorithm\":\"recursive-length\",\"params\":{\"len\":\"30\",\"overlap\":\"0\"}}}}";

    private final TitleBoundarySliceStrategy titleBoundary = new TitleBoundarySliceStrategy();

    private final ParagraphSliceStrategy paragraph = new ParagraphSliceStrategy();

    private ViewElement element(String id, String text, Integer page) {
        ViewElement element = new ViewElement();
        element.setElementId(id);
        element.setType(UnifiedElementType.PARAGRAPH.name());
        element.setStatus(ViewElementStatus.NORMAL.name());
        element.setNormalizedText(text);
        element.setDisplayText(text);
        element.setPage(page);
        return element;
    }

    private SliceContext context(String configJson) {
        SliceContext context = new SliceContext();
        context.setView(new PreprocessView());
        context.setStrategy(new ChunkStrategyParser(new ChunkProperties()).parse(configJson));
        context.setTitlePath(List.of("第一章 投标人须知"));
        context.setBodyBuffer(new ArrayList<>());
        context.setFallback(new RecursiveLengthFallback());
        return context;
    }

    private String filler(int length) {
        return "甲".repeat(length);
    }

    @Test
    void sectionWithinLimitShouldYieldOneParagraphChunk() {
        SliceContext context = context("{\"routes\":{\"body\":{\"algorithm\":\"title-boundary\"}}}");
        titleBoundary.slice(element("n-1", "一节内的短文本。", 3), context);

        List<Chunk> chunks = titleBoundary.flush(context);

        assertEquals(1, chunks.size());
        assertEquals(ChunkContentType.PARAGRAPH.name(), chunks.getFirst().getContentType());
        assertEquals("第一章 投标人须知", chunks.getFirst().getTitlePath());
        assertEquals(List.of("n-1"), chunks.getFirst().getSourceElementIds());
        assertEquals(List.of(3), chunks.getFirst().getPageRange());
        assertEquals(chunks.getFirst().getContent().length(), chunks.getFirst().getCharCount());
    }

    @Test
    void sectionOverLimitShouldFallBackWithReason() {
        SliceContext context = context(TITLE_BOUNDARY_CONFIG);
        titleBoundary.slice(element("n-1", filler(250), 4), context);

        List<Chunk> chunks = titleBoundary.flush(context);

        assertTrue(chunks.size() >= 2, "超长一节应降级成多片");
        for (Chunk chunk : chunks) {
            assertEquals(ChunkContentType.FALLBACK.name(), chunk.getContentType());
            assertEquals(BodyChunkSupport.REASON_TITLE_BOUNDARY_TOO_LONG, chunk.getFallbackReason());
            assertTrue(chunk.getCharCount() <= 50, "降级片长应受兜底 len 约束");
            assertEquals(List.of("n-1"), chunk.getSourceElementIds());
        }
    }

    @Test
    void emptyBufferShouldYieldNoChunks() {
        assertTrue(titleBoundary.flush(context(TITLE_BOUNDARY_CONFIG)).isEmpty());
    }

    @Test
    void paragraphOverSoftMaxShouldFallBackWithReason() {
        SliceContext context = context(PARAGRAPH_CONFIG);

        List<Chunk> chunks = paragraph.slice(element("n-1", filler(200), 2), context);

        assertFalse(chunks.isEmpty(), "超过软上限应直接走兜底降级");
        for (Chunk chunk : chunks) {
            assertEquals(ChunkContentType.FALLBACK.name(), chunk.getContentType());
            assertEquals(BodyChunkSupport.REASON_PARAGRAPH_TOO_LONG, chunk.getFallbackReason());
            assertTrue(chunk.getCharCount() <= 30);
        }
    }
}
