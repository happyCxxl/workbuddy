package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.enums.embed.EmbedRecordStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量化详情 VO 组装器单测：集合摘要三态统计（SUCCESS/FAILED/SKIPPED 计数、CACHED 不参与）/ 记录条目映射。
 *
 * @author cxxl
 */
class EmbedVoAssemblerTest {

    private final EmbedVoAssembler assembler = new EmbedVoAssembler();

    private WbEmbeddingSet embeddingSet() {
        WbEmbeddingSet set = new WbEmbeddingSet();
        set.setEmbeddingSetId("es-cs-1-embed-default-v1");
        set.setStrategyVersion("embed-default-v1");
        set.setModel("huawei-BGE-M3");
        set.setDimension(1024);
        set.setMetric("COSINE");
        set.setNormalized(true);
        set.setRecordCount(5);
        set.setCachedCount(2);
        return set;
    }

    private WbEmbeddingRecord record(String embeddingId, String status) {
        WbEmbeddingRecord record = new WbEmbeddingRecord();
        record.setEmbeddingId(embeddingId);
        record.setChunkId("chunk-0001");
        record.setContentType("PARAGRAPH");
        record.setInputText("正文片");
        record.setTokenCount(3);
        record.setRequestId("req-1");
        record.setStatus(status);
        record.setCacheHit(EmbedRecordStatus.CACHED.name().equals(status));
        return record;
    }

    @Test
    void summaryShouldCountThreeStatusesAndIgnoreCached() {
        List<WbEmbeddingRecord> records = List.of(
                record("emb-0001", "SUCCESS"),
                record("emb-0002", "SUCCESS"),
                record("emb-0003", "CACHED"),
                record("emb-0004", "CACHED"),
                record("emb-0005", "SKIPPED"));

        var summary = assembler.toSummary(embeddingSet(), records);

        assertEquals("es-cs-1-embed-default-v1", summary.getEmbeddingSetId());
        assertEquals("embed-default-v1", summary.getStrategyVersion());
        assertEquals("huawei-BGE-M3", summary.getModel());
        assertEquals(1024, summary.getDimension());
        assertEquals("COSINE", summary.getMetric());
        assertTrue(summary.getNormalized());
        assertEquals(5, summary.getRecordCount());
        assertEquals(2, summary.getCachedCount());
        assertEquals(2, summary.getSuccessCount());
        assertEquals(0, summary.getFailedCount());
        assertEquals(1, summary.getSkippedCount());
    }

    @Test
    void summaryShouldCountFailedAndIgnoreUnknownStatus() {
        List<WbEmbeddingRecord> records = List.of(
                record("emb-0001", "FAILED"),
                record("emb-0002", "FAILED"),
                record("emb-0003", "SUCCESS"));

        var summary = assembler.toSummary(embeddingSet(), records);

        assertEquals(2, summary.getFailedCount());
        assertEquals(1, summary.getSuccessCount());
        assertEquals(0, summary.getSkippedCount());
    }

    @Test
    void summaryShouldIgnoreUnknownStatus() {
        var summary = assembler.toSummary(embeddingSet(), List.of(record("emb-0001", "PENDING")));

        assertEquals(0, summary.getSuccessCount());
        assertEquals(0, summary.getFailedCount());
        assertEquals(0, summary.getSkippedCount());
    }

    @Test
    void recordItemShouldMapFields() {
        var items = assembler.toRecordItemVOs(List.of(record("emb-0001", "SUCCESS")));

        assertEquals(1, items.size());
        assertEquals("emb-0001", items.get(0).getEmbeddingId());
        assertEquals("chunk-0001", items.get(0).getChunkId());
        assertEquals("PARAGRAPH", items.get(0).getContentType());
        assertEquals("正文片", items.get(0).getInputText());
        assertEquals(3, items.get(0).getTokenCount());
        assertEquals("req-1", items.get(0).getRequestId());
        assertEquals("SUCCESS", items.get(0).getStatus());
        assertTrue(!items.get(0).getCacheHit());
    }
}
