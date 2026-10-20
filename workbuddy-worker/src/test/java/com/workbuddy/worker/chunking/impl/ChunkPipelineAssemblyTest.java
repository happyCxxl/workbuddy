package com.workbuddy.worker.chunking.impl;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkOutcome;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.workbuddy.worker.chunking.impl.ChunkPipelineFixtures.context;
import static com.workbuddy.worker.chunking.impl.ChunkPipelineFixtures.document;
import static com.workbuddy.worker.chunking.impl.ChunkPipelineFixtures.pipeline;
import static com.workbuddy.worker.chunking.impl.ChunkPipelineFixtures.tableElement;
import static com.workbuddy.worker.chunking.impl.ChunkPipelineFixtures.tableStruct;
import static com.workbuddy.worker.chunking.impl.ChunkPipelineFixtures.unifiedElement;
import static com.workbuddy.worker.chunking.impl.ChunkPipelineFixtures.view;
import static com.workbuddy.worker.chunking.impl.ChunkPipelineFixtures.viewElement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片管线装配单测：标题路径上限、确定性、标题入正文与后置处理链（结构重叠/碎片合并）。
 * 用例失败即表示对应章节装配或后置处理行为被改动。
 */
class ChunkPipelineAssemblyTest {

    /**
     * 守护 {@code titlePathShouldCapAtThreeLevels}：该场景行为改动即失败。
     */
    @Test
    void titlePathShouldCapAtThreeLevels() {
        List<ViewElement> elements = new ArrayList<>();
        List<UnifiedElement> structures = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            elements.add(viewElement("t" + i, UnifiedElementType.TITLE.name(), 1,
                    "标题" + i, ViewElementStatus.NORMAL.name()));
            structures.add(unifiedElement("t" + i, UnifiedElementType.TITLE.name(), i, null));
            elements.add(viewElement("p" + i, UnifiedElementType.PARAGRAPH.name(), 1,
                    "段落" + i, ViewElementStatus.NORMAL.name()));
            structures.add(unifiedElement("p" + i, UnifiedElementType.PARAGRAPH.name(), null, null));
        }

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(structures)));

        Chunk last = outcome.getChunkSet().getChunks().getLast();
        assertEquals("标题2 > 标题3 > 标题4", last.getTitlePath());
    }

    /**
     * 守护 {@code sameInputSameStrategyShouldBeDeterministic}：该场景行为改动即失败。
     */
    @Test
    void sameInputSameStrategyShouldBeDeterministic() {
        List<ViewElement> elements = List.of(
                viewElement("t1", UnifiedElementType.TITLE.name(), 1, "第一章", ViewElementStatus.NORMAL.name()),
                viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1,
                        "正文内容", ViewElementStatus.NORMAL.name()));
        List<UnifiedElement> structures = List.of(
                unifiedElement("t1", UnifiedElementType.TITLE.name(), 1, null),
                unifiedElement("n-1", UnifiedElementType.PARAGRAPH.name(), null, null));

        ChunkOutcome first = pipeline().chunk(context(view(elements), document(structures)));
        ChunkOutcome second = pipeline().chunk(context(view(elements), document(structures)));

        assertEquals(first.getChunkSet().getChunkSetId(), second.getChunkSet().getChunkSetId());
        assertEquals(first.getChunkSet().getChunkCount(), second.getChunkSet().getChunkCount());
        assertEquals(first.getChunkSet().getChunks().getFirst().getContent(),
                second.getChunkSet().getChunks().getFirst().getContent());
    }

    /**
     * 守护 {@code titleInContentOffShouldKeepContentClean}：该场景行为改动即失败。
     */
    @Test
    void titleInContentOffShouldKeepContentClean() {
        List<ViewElement> elements = List.of(
                viewElement("t1", UnifiedElementType.TITLE.name(), 1,
                        "第一章 投标人须知", ViewElementStatus.NORMAL.name()),
                tableElement());
        List<UnifiedElement> structures = List.of(
                unifiedElement("t1", UnifiedElementType.TITLE.name(), 1, null),
                tableStruct());

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(structures),
                "{\"pipeline\":{\"titleInContent\":\"OFF\"}}"));

        Chunk tableChunk = outcome.getChunkSet().getChunks().stream()
                .filter(c -> ChunkContentType.TABLE.name().equals(c.getContentType())).findFirst().orElse(null);
        assertNotNull(tableChunk);
        assertTrue(tableChunk.getContent().startsWith("| 评分项 | 分值 |"));
        assertEquals("第一章 投标人须知", tableChunk.getTitlePath());
    }

    /**
     * 守护 {@code structureOverlapShouldPrefixAdjacentChunks}：该场景行为改动即失败。
     */
    @Test
    void structureOverlapShouldPrefixAdjacentChunks() {
        String longText = "a".repeat(900);
        List<ViewElement> elements = List.of(
                viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1, longText,
                        ViewElementStatus.NORMAL.name()),
                viewElement("n-2", UnifiedElementType.PARAGRAPH.name(), 1, "b".repeat(500),
                        ViewElementStatus.NORMAL.name()));

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(List.of()),
                "{\"pipeline\":{\"structureOverlap\":\"50\",\"titleInContent\":\"OFF\"}}"));

        List<Chunk> chunks = outcome.getChunkSet().getChunks();
        assertEquals(2, chunks.size());
        String first = chunks.getFirst().getContent();
        assertTrue(chunks.get(1).getContent().startsWith(first.substring(first.length() - 50) + "\n"));
    }

    /**
     * 守护 {@code minMergeShouldMergeAdjacentBodyFragment}：该场景行为改动即失败。
     */
    @Test
    void minMergeShouldMergeAdjacentBodyFragment() {
        List<ViewElement> elements = List.of(
                viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1, "a".repeat(900),
                        ViewElementStatus.NORMAL.name()),
                viewElement("n-2", UnifiedElementType.PARAGRAPH.name(), 2, "碎片",
                        ViewElementStatus.NORMAL.name()));

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(List.of()),
                "{\"pipeline\":{\"minMergeLen\":\"300\",\"titleInContent\":\"OFF\"}}"));

        List<Chunk> chunks = outcome.getChunkSet().getChunks();
        assertEquals(1, chunks.size());
        assertTrue(chunks.getFirst().getContent().endsWith("碎片"));
        assertEquals(903, chunks.getFirst().getCharCount());
    }
}
