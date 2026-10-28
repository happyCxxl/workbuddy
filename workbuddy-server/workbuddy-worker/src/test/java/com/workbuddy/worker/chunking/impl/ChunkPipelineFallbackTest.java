package com.workbuddy.worker.chunking.impl;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkOutcome;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.worker.chunking.ChunkContext;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.slice.SliceStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.workbuddy.worker.chunking.impl.ChunkPipelineFixtures.context;
import static com.workbuddy.worker.chunking.impl.ChunkPipelineFixtures.document;
import static com.workbuddy.worker.chunking.impl.ChunkPipelineFixtures.pipeline;
import static com.workbuddy.worker.chunking.impl.ChunkPipelineFixtures.pipelineWith;
import static com.workbuddy.worker.chunking.impl.ChunkPipelineFixtures.view;
import static com.workbuddy.worker.chunking.impl.ChunkPipelineFixtures.viewElement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片管线降级与失败单测：超长兜底、none 兜底、空内容/无策略错误码、切片器异常隔离与未支持算法报错。
 * 用例失败即表示对应兜底/错误码行为被改动。
 */
class ChunkPipelineFallbackTest {

    /**
     * 守护 {@code missingStrategyShouldFailWithChunkFailed}：该场景行为改动即失败。
     */
    @Test
    void missingStrategyShouldFailWithChunkFailed() {
        // 上下文无策略（快照缺失或损坏由 Runner 拦截）：管线自身同样拒绝，不构造默认策略
        List<ViewElement> elements = List.of(viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1, "正文内容",
                ViewElementStatus.NORMAL.name()));
        ChunkContext context = context(view(elements), document(List.of()));
        context.setStrategy(null);

        ChunkOutcome outcome = pipeline().chunk(context);

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.CHUNK_FAILED.name(), outcome.getErrorCode());
        assertEquals("任务未带切片策略快照，无法切片", outcome.getErrorMsg());
        assertNull(outcome.getChunkSet());
    }

    /**
     * 守护 {@code emptyViewShouldFailWithChunkEmpty}：该场景行为改动即失败。
     */
    @Test
    void emptyViewShouldFailWithChunkEmpty() {
        ChunkOutcome outcome = pipeline().chunk(context(view(List.of()), document(List.of())));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.CHUNK_EMPTY.name(), outcome.getErrorCode());
    }

    /**
     * 守护 {@code allExcludedShouldFailWithChunkEmpty}：该场景行为改动即失败。
     */
    @Test
    void allExcludedShouldFailWithChunkEmpty() {
        List<ViewElement> elements = List.of(viewElement("h-1", UnifiedElementType.HEADER.name(), 1,
                "页眉", ViewElementStatus.EXCLUDED_HEADER.name()));

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(List.of())));

        assertEquals(PipelineTaskStatus.FAILED, outcome.getSuggestedStatus());
        assertEquals(PipelineTaskErrorCode.CHUNK_EMPTY.name(), outcome.getErrorCode());
    }

    /**
     * 守护 {@code overlongParagraphShouldFallbackRecursively}：该场景行为改动即失败。
     */
    @Test
    void overlongParagraphShouldFallbackRecursively() {
        List<ViewElement> elements = List.of(viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1,
                "a".repeat(1200), ViewElementStatus.NORMAL.name()));

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(List.of())));

        List<Chunk> fallbacks = outcome.getChunkSet().getChunks().stream()
                .filter(c -> ChunkContentType.FALLBACK.name().equals(c.getContentType())).toList();
        assertEquals(3, fallbacks.size());
        assertTrue(fallbacks.stream().allMatch(c -> "超长段落递归降级".equals(c.getFallbackReason())));
        assertTrue(fallbacks.stream().allMatch(c -> c.getCharCount() <= 500));
    }

    /**
     * 守护 {@code slicerExceptionShouldIsolateToPartialSuccess}：该场景行为改动即失败。
     */
    @Test
    void slicerExceptionShouldIsolateToPartialSuccess() {
        ChunkPipeline pipeline = pipelineWith(new BrokenTableStrategy());
        List<ViewElement> elements = List.of(
                viewElement("t-1", UnifiedElementType.TABLE.name(), 1, null, ViewElementStatus.NORMAL.name()),
                viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1,
                        "正文照常切片", ViewElementStatus.NORMAL.name()));

        ChunkOutcome outcome = pipeline.chunk(context(view(elements), document(List.of())));

        assertEquals(PipelineTaskStatus.PARTIAL_SUCCESS, outcome.getSuggestedStatus());
        assertTrue(outcome.getWarnings().stream().anyMatch(w -> w.contains("TABLE")));
        // 正文片照产（异常只影响该切片器产物）
        assertEquals(1, outcome.getChunkSet().getChunkCount());
        assertEquals(ChunkContentType.PARAGRAPH.name(), outcome.getChunkSet().getChunks().getFirst().getContentType());
    }

    /**
     * 守护 {@code noneFallbackShouldKeepOverlongSingleChunk}：该场景行为改动即失败。
     */
    @Test
    void noneFallbackShouldKeepOverlongSingleChunk() {
        List<ViewElement> elements = List.of(viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1,
                "a".repeat(1200), ViewElementStatus.NORMAL.name()));

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(List.of()),
                "{\"routes\":{\"fallback\":{\"algorithm\":\"none\"}}}"));

        List<Chunk> chunks = outcome.getChunkSet().getChunks();
        assertEquals(1, chunks.size());
        assertEquals(ChunkContentType.FALLBACK.name(), chunks.getFirst().getContentType());
        assertEquals(1200, chunks.getFirst().getCharCount());
    }

    /**
     * 守护 {@code unsupportedAlgorithmShouldFailClearly}：该场景行为改动即失败。
     */
    @Test
    void unsupportedAlgorithmShouldFailClearly() {
        List<ViewElement> elements = List.of(viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1,
                "正文内容", ViewElementStatus.NORMAL.name()));

        assertThrows(WorkbuddyException.class, () -> pipeline().chunk(
                context(view(elements), document(List.of()),
                        "{\"routes\":{\"body\":{\"algorithm\":\"semantic\"}}}")));
    }

    /** 必抛异常的表格切片器（模拟切片器缺陷：只影响该类型产物） */
    private static final class BrokenTableStrategy implements SliceStrategy {
        @Override
        public ChunkAlgorithm algorithm() {
            return ChunkAlgorithm.TABLE_ROW_SLICE;
        }

        @Override
        public List<Chunk> slice(ViewElement element, SliceContext context) {
            throw new IllegalStateException("boom");
        }
    }
}
