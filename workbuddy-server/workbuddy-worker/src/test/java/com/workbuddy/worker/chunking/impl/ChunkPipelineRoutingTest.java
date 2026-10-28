package com.workbuddy.worker.chunking.impl;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkOutcome;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import org.junit.jupiter.api.Test;

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
 * 切片管线路由单测：四路分派、剔除跳过、表格边界、表格并入正文流与前导段落。
 * 用例失败即表示对应路由/分组行为被改动。
 */
class ChunkPipelineRoutingTest {

    /**
     * 守护 {@code fullChainShouldProduceMixedChunksWithParentChildAndTitlePath}：该场景行为改动即失败。
     */
    @Test
    void fullChainShouldProduceMixedChunksWithParentChildAndTitlePath() {
        List<ViewElement> elements = List.of(
                viewElement("t1", UnifiedElementType.TITLE.name(), 1,
                        "第一章 投标人须知", ViewElementStatus.NORMAL.name()),
                viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1,
                        "投标保证金为人民币叁佰万元整。", ViewElementStatus.NORMAL.name()),
                viewElement("n-2", UnifiedElementType.PARAGRAPH.name(), 1,
                        "本须知适用于参与本次采购活动的所有供应商。", ViewElementStatus.NORMAL.name()),
                viewElement("t2", UnifiedElementType.TITLE.name(), 2,
                        "1.3 评分标准", ViewElementStatus.NORMAL.name()),
                tableElement(),
                viewElement("i-1", UnifiedElementType.IMAGE.name(), 2, null, ViewElementStatus.NORMAL.name()));

        List<UnifiedElement> structures = List.of(
                unifiedElement("t1", UnifiedElementType.TITLE.name(), 1, null),
                unifiedElement("t2", UnifiedElementType.TITLE.name(), 2, null),
                tableStruct(),
                unifiedElement("i-1", UnifiedElementType.IMAGE.name(), null, "附件二：资质证书（扫描件）"),
                unifiedElement("n-1", UnifiedElementType.PARAGRAPH.name(), null, null),
                unifiedElement("n-2", UnifiedElementType.PARAGRAPH.name(), null, null));

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(structures)));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        ChunkSet chunkSet = outcome.getChunkSet();
        assertEquals("cs-doc-10-chunk-hybrid-v1", chunkSet.getChunkSetId());
        assertEquals(6, outcome.getStepLogs().size());

        // 父子：两个章节各一个父片（SECTION）
        List<Chunk> parents = chunkSet.getChunks().stream()
                .filter(c -> ChunkContentType.SECTION.name().equals(c.getContentType())).toList();
        assertEquals(2, parents.size());
        assertEquals("第一章 投标人须知", parents.getFirst().getTitlePath());
        assertEquals("第一章 投标人须知 > 1.3 评分标准", parents.get(1).getTitlePath());

        // 正文子片挂父片
        Chunk bodyChild = chunkSet.getChunks().stream()
                .filter(c -> ChunkContentType.PARAGRAPH.name().equals(c.getContentType())).findFirst().orElse(null);
        assertNotNull(bodyChild);
        assertEquals(parents.getFirst().getChunkId(), bodyChild.getParentChunkId());
        assertEquals("第一章 投标人须知", bodyChild.getTitlePath());
        assertTrue(bodyChild.getContent().contains("投标保证金"));

        // 表格片：Markdown 表格形态（表头行 + 分隔行 + 数据行），评分项与分值同片；标题入正文默认开（标题链前缀）
        Chunk tableChunk = chunkSet.getChunks().stream()
                .filter(c -> ChunkContentType.TABLE.name().equals(c.getContentType())).findFirst().orElse(null);
        assertNotNull(tableChunk);
        assertEquals("第一章 投标人须知 > 1.3 评分标准\n| 评分项 | 分值 |\n|---|---|\n| A1 报价 | 30 |",
                tableChunk.getContent());
        assertEquals(parents.get(1).getChunkId(), tableChunk.getParentChunkId());
        assertEquals("t-1", tableChunk.getTableRef());
        assertTrue(tableChunk.getSourceElementIds().contains("tc-1"));

        // 图片片：图注占位
        Chunk imageChunk = chunkSet.getChunks().stream()
                .filter(c -> ChunkContentType.IMAGE.name().equals(c.getContentType())).findFirst().orElse(null);
        assertNotNull(imageChunk);
        assertTrue(imageChunk.getContent().contains("图注：附件二：资质证书（扫描件）"));

        // 编号与顺序
        assertEquals("chunk-0001", chunkSet.getChunks().getFirst().getChunkId());
        assertTrue(chunkSet.getChunks().stream().allMatch(c -> c.getTokenCount() > 0));
    }

    /**
     * 守护 {@code tableShouldBreakBodyGroupAndKeepDocumentOrder}：该场景行为改动即失败。
     */
    @Test
    void tableShouldBreakBodyGroupAndKeepDocumentOrder() {
        List<ViewElement> elements = List.of(
                viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1,
                        "表格前段落", ViewElementStatus.NORMAL.name()),
                tableElement(),
                viewElement("n-2", UnifiedElementType.PARAGRAPH.name(), 3,
                        "表格后段落", ViewElementStatus.NORMAL.name()));
        List<UnifiedElement> structures = List.of(tableStruct());

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(structures)));

        // 表格打断正文聚合：三段各成一片、顺序符合文档顺序
        List<Chunk> chunks = outcome.getChunkSet().getChunks();
        assertEquals(3, chunks.size());
        assertEquals(ChunkContentType.PARAGRAPH.name(), chunks.getFirst().getContentType());
        assertEquals("表格前段落", chunks.getFirst().getContent());
        assertEquals(List.of(1), chunks.getFirst().getPageRange());
        assertEquals(ChunkContentType.TABLE.name(), chunks.get(1).getContentType());
        assertEquals(ChunkContentType.PARAGRAPH.name(), chunks.get(2).getContentType());
        assertEquals("表格后段落", chunks.get(2).getContent());
        assertEquals(List.of(3), chunks.get(2).getPageRange());
        assertTrue(chunks.stream().noneMatch(c -> c.getContent().contains("表格前段落\n表格后段落")));
    }

    /**
     * 守护 {@code excludedAndRepeatedShouldBeSkipped}：该场景行为改动即失败。
     */
    @Test
    void excludedAndRepeatedShouldBeSkipped() {
        List<ViewElement> elements = List.of(
                viewElement("h-1", UnifiedElementType.HEADER.name(), 1,
                        "页眉文本", ViewElementStatus.EXCLUDED_HEADER.name()),
                viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1,
                        "重复段落", ViewElementStatus.REPEATED.name()),
                viewElement("n-2", UnifiedElementType.PARAGRAPH.name(), 1,
                        "正常段落内容", ViewElementStatus.NORMAL.name()));

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(List.of())));

        assertEquals(1, outcome.getChunkSet().getChunkCount());
        assertEquals("正常段落内容", outcome.getChunkSet().getChunks().getFirst().getContent());
    }

    /**
     * 守护 {@code tableInBodyFlowShouldMergeTableIntoBodyChunk}：该场景行为改动即失败。
     */
    @Test
    void tableInBodyFlowShouldMergeTableIntoBodyChunk() {
        List<ViewElement> elements = List.of(
                viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1,
                        "表格前段落", ViewElementStatus.NORMAL.name()),
                tableElement(),
                viewElement("n-2", UnifiedElementType.PARAGRAPH.name(), 3,
                        "表格后段落", ViewElementStatus.NORMAL.name()));
        List<UnifiedElement> structures = List.of(tableStruct());

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(structures),
                "{\"pipeline\":{\"tableInBodyFlow\":\"ON\"}}"));

        List<Chunk> chunks = outcome.getChunkSet().getChunks();
        assertEquals(1, chunks.size());
        Chunk chunk = chunks.getFirst();
        assertEquals(ChunkContentType.PARAGRAPH.name(), chunk.getContentType());
        assertTrue(chunk.getContent().contains("表格前段落"));
        assertTrue(chunk.getContent().contains("| 评分项 | 分值 |"));
        assertTrue(chunk.getContent().contains("表格后段落"));
    }

    /**
     * 守护 {@code contextMergedTableShouldCarryLeadParagraph}：该场景行为改动即失败。
     */
    @Test
    void contextMergedTableShouldCarryLeadParagraph() {
        List<ViewElement> elements = List.of(
                viewElement("n-1", UnifiedElementType.PARAGRAPH.name(), 1,
                        "引出表格的说明段落", ViewElementStatus.NORMAL.name()),
                tableElement());
        List<UnifiedElement> structures = List.of(tableStruct());

        ChunkOutcome outcome = pipeline().chunk(context(view(elements), document(structures),
                "{\"routes\":{\"table\":{\"algorithm\":\"context-merged\"}}}"));

        List<Chunk> tables = outcome.getChunkSet().getChunks().stream()
                .filter(c -> ChunkContentType.TABLE.name().equals(c.getContentType())).toList();
        assertEquals(1, tables.size());
        assertTrue(tables.getFirst().getContent().startsWith("引出表格的说明段落\n| 评分项 | 分值 |"));
    }
}
