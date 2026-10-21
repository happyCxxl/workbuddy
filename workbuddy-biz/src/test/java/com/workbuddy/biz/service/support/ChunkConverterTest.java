package com.workbuddy.biz.service.support;

import com.workbuddy.common.convert.KnowledgeConverter;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.entity.WbChunk;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.biz.service.support.stats.ChunkStatsSupport;
import com.workbuddy.common.convert.KnowledgeConverterImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 切片详情 VO 组装器单测（纯映射无状态类，用真实实例断言）。
 */
class ChunkConverterTest {

    private final KnowledgeConverter assembler = new KnowledgeConverterImpl();

    private WbChunk chunk(String id, String type, String parentChunkId, int chars) {
        WbChunk chunk = new WbChunk();
        chunk.setChunkId(id);
        chunk.setContentType(type);
        chunk.setParentChunkId(parentChunkId);
        chunk.setCharCount(chars);
        chunk.setContent("内容" + id);
        return chunk;
    }

    /**
     * 切片摘要：父子计数、类型分布与平均片长。
     *
     * <p>守护 {@code toSummary}：计数或均值口径错即失败。
     */
    @Test
    void toSummaryShouldCountParentChildTypesAndAvgChars() {
        WbChunkSet chunkSet = new WbChunkSet();
        chunkSet.setChunkCount(4);
        chunkSet.setTotalChars(1000);
        List<WbChunk> chunks = List.of(
                chunk("c-1", ChunkContentType.SECTION.name(), null, 300),
                chunk("c-2", ChunkContentType.PARAGRAPH.name(), "c-1", 300),
                chunk("c-3", ChunkContentType.TABLE.name(), "c-1", 250),
                chunk("c-4", ChunkContentType.PARAGRAPH.name(), null, 150));

        var summary = ChunkStatsSupport.summaryOf(chunkSet, chunks);

        assertEquals(4, summary.getChunkCount());
        assertEquals(1000, summary.getTotalChars());
        assertEquals(250, summary.getAvgChars());
        assertEquals(1, summary.getParentChunkCount());
        assertEquals(2, summary.getChildChunkCount());
        assertEquals(2, summary.getTypeCounts().get(ChunkContentType.PARAGRAPH.name()));
        assertEquals(1, summary.getTypeCounts().get(ChunkContentType.TABLE.name()));
    }

    /**
     * 切片条目：DB 列与产物侧列逐列合并。
     *
     * <p>守护 {@code toChunkItemVOs}：漏搬任一列即失败。
     */
    @Test
    void toChunkItemVOsShouldMapAllFields() {
        WbChunk chunk = chunk("chunk-0001", ChunkContentType.PARAGRAPH.name(), "chunk-0000", 42);
        chunk.setTitlePath("第一章 > 第一节");
        chunk.setPageRange("1-2");
        chunk.setOrderNo(1);
        chunk.setTokenCount(28);
        chunk.setTableRef("tbl-1");
        Chunk productChunk = new Chunk();
        productChunk.setChunkId("chunk-0001");
        productChunk.setSourceElementIds(List.of("el-1", "el-2"));
        productChunk.setFallbackReason("超长段落按 500/50 递归切分");

        var vos = assembler.toChunkItemVOs(List.of(chunk), Map.of("chunk-0001", productChunk));

        assertEquals(1, vos.size());
        assertEquals("chunk-0001", vos.getFirst().getChunkId());
        assertEquals("chunk-0000", vos.getFirst().getParentChunkId());
        assertEquals("内容chunk-0001", vos.getFirst().getContent());
        assertEquals(ChunkContentType.PARAGRAPH.name(), vos.getFirst().getContentType());
        assertEquals("第一章 > 第一节", vos.getFirst().getTitlePath());
        assertEquals("1-2", vos.getFirst().getPageRange());
        assertEquals("tbl-1", vos.getFirst().getTableRef());
        assertEquals(1, vos.getFirst().getOrderNo());
        assertEquals(42, vos.getFirst().getCharCount());
        assertEquals(28, vos.getFirst().getTokenCount());
        // 来源元素个数与兜底原因只在切片产物里，DB 无这两列
        assertEquals(2, vos.getFirst().getSourceElementCount());
        assertEquals("超长段落按 500/50 递归切分", vos.getFirst().getFallbackReason());
    }
}
