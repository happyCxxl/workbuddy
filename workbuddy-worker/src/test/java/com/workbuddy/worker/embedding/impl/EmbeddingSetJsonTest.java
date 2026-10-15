package com.workbuddy.worker.embedding.impl;

import com.workbuddy.common.domain.embed.EmbeddingRecord;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.common.utils.JsonUtil;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EmbeddingSet JSON 往返单测：向量本体/记录元数据/目录冗余完整往返。
 *
 * @author cxxl
 */
class EmbeddingSetJsonTest {

    @Test
    void jsonRoundTripShouldPreserveRecordsAndVectors() {
        EmbeddingSet set = new EmbeddingSet();
        set.setEmbeddingSetId("es-cs-1-embed-default-v1");
        set.setFileResultId(5L);
        set.setChunkSetRef(9L);
        set.setChunkSetId("cs-1");
        set.setStrategyVersion("embed-default-v1");
        set.setModel("text-embedding-v4");
        set.setDimension(1024);
        set.setMetric("COSINE");
        set.setNormalized(true);
        set.setRecordCount(2);
        set.setCachedCount(1);

        EmbeddingRecord fresh = new EmbeddingRecord();
        fresh.setEmbeddingId("emb-0001");
        fresh.setChunkId("chunk-0001");
        fresh.setContentType("PARAGRAPH");
        fresh.setInputText("第一段");
        fresh.setInputTextHash("hash-1");
        fresh.setTokenCount(2);
        fresh.setRequestId("req-1");
        fresh.setStatus("SUCCESS");
        fresh.setCacheHit(false);
        fresh.setVector(List.of(0.1f, 0.2f, 0.3f));

        EmbeddingRecord cached = new EmbeddingRecord();
        cached.setEmbeddingId("emb-0002");
        cached.setChunkId("chunk-0002");
        cached.setContentType("PARAGRAPH");
        cached.setInputText("第二段");
        cached.setInputTextHash("hash-2");
        cached.setTokenCount(2);
        cached.setStatus("CACHED");
        cached.setCacheHit(true);
        cached.setVector(List.of(0.4f, 0.5f, 0.6f));

        set.setRecords(List.of(fresh, cached));

        String json = JsonUtil.toJsonStr(set);
        EmbeddingSet restored = JsonUtil.toObject(json, EmbeddingSet.class);

        assertEquals(set.getEmbeddingSetId(), restored.getEmbeddingSetId());
        assertEquals(set.getChunkSetRef(), restored.getChunkSetRef());
        assertEquals(set.getDimension(), restored.getDimension());
        assertEquals(set.getMetric(), restored.getMetric());
        assertTrue(restored.isNormalized());
        assertEquals(set.getRecordCount(), restored.getRecordCount());
        assertEquals(set.getCachedCount(), restored.getCachedCount());
        assertEquals(2, restored.getRecords().size());
        assertEquals(List.of(0.1f, 0.2f, 0.3f), restored.getRecords().get(0).getVector());
        assertTrue(restored.getRecords().get(1).isCacheHit());
        assertFalse(restored.getRecords().get(0).isCacheHit());
    }
}
