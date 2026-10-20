package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.embed.EmbeddingRecord;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.biz.service.support.common.EmbedRowSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 向量化落库行装配单测。
 *
 * <p>守护 {@code setRow}/{@code recordRow} 的字段口径：产物集合的每一列（策略、模型、维度、度量、归一化、
 * 计数、对象位置）与记录行的每一列（片定位、输入文本与指纹、请求号、状态）漏搬都会让详情与对账读不到。
 */
class EmbedRowSupportTest {

    /**
     * 集合行：文件、集合、策略、模型/维度/度量/归一化、计数、产物位置与状态逐列落库。
     *
     * <p>守护 {@code setRow}：任一列漏搬都会让集合账本与产物对象脱节。
     */
    @Test
    void setRowShouldCarryEveryColumn() {
        EmbeddingSet set = new EmbeddingSet();
        set.setEmbeddingSetId("set-1");
        set.setChunkSetRef(60L);
        set.setModel("text-embedding-v4");
        set.setDimension(1024);
        set.setMetric("COSINE");
        set.setNormalized(true);
        set.setRecordCount(12);
        set.setCachedCount(3);
        set.setRecords(List.of(new EmbeddingRecord()));
        ObjectRef ref = new ObjectRef(9L, StorageType.MINIO, "artifacts", "embed/set-1.json");

        WbEmbeddingSet row = EmbedRowSupport.setRow(10L, set, "embed-default-v1", ref);

        assertEquals(10L, row.getFileResultId());
        assertEquals(60L, row.getChunkSetRef());
        assertEquals("set-1", row.getEmbeddingSetId());
        assertEquals("embed-default-v1", row.getStrategyVersion());
        assertEquals("text-embedding-v4", row.getModel());
        assertEquals(1024, row.getDimension());
        assertEquals("COSINE", row.getMetric());
        assertEquals(Boolean.TRUE, row.getNormalized());
        assertEquals(12, row.getRecordCount());
        assertEquals(3, row.getCachedCount());
        assertEquals(RowStatus.ACTIVE.name(), row.getStatus());
        assertEquals("embed/set-1.json", row.getArtifactId());
        assertEquals(StorageType.MINIO.getCode(), row.getStorageType());
        assertEquals(9L, row.getStorageSourceId());
        assertEquals("artifacts", row.getBucket());
    }

    /**
     * 集合行：归一化关闭时落 false（不能被默认值吃掉）。
     *
     * <p>守护 {@code setRow} 的归一化搬运：恒 true 会让 L2 与非归一化的组合被改写。
     */
    @Test
    void setRowShouldCarryNormalizedFalse() {
        EmbeddingSet set = new EmbeddingSet();
        set.setNormalized(false);

        WbEmbeddingSet row = EmbedRowSupport.setRow(10L, set, "embed-default-v1", ref());

        assertEquals(Boolean.FALSE, row.getNormalized());
        assertNull(row.getEmbeddingSetId());
    }

    /**
     * 记录行：片定位、输入文本与指纹、token 数、请求号与状态逐列搬运。
     *
     * <p>守护 {@code recordRow}：漏搬定位列会让详情对不上片，漏搬指纹会让缓存判定失效。
     */
    @Test
    void recordRowShouldCarryEveryColumn() {
        EmbeddingRecord record = new EmbeddingRecord();
        record.setEmbeddingId("emb-1");
        record.setChunkId("chunk-0001");
        record.setContentType("PARAGRAPH");
        record.setParentChunkId("chunk-0000");
        record.setInputText("投标保证金叁万元");
        record.setInputTextHash("sha-1");
        record.setTokenCount(9);
        record.setRequestId("req-1");
        record.setStatus("SUCCESS");
        record.setCacheHit(true);

        WbEmbeddingRecord row = EmbedRowSupport.recordRow(70L, record);

        assertEquals(70L, row.getEmbeddingSetId());
        assertEquals("emb-1", row.getEmbeddingId());
        assertEquals("chunk-0001", row.getChunkId());
        assertEquals("PARAGRAPH", row.getContentType());
        assertEquals("chunk-0000", row.getParentChunkId());
        assertEquals("投标保证金叁万元", row.getInputText());
        assertEquals("sha-1", row.getInputTextHash());
        assertEquals(9, row.getTokenCount());
        assertEquals("req-1", row.getRequestId());
        assertEquals("SUCCESS", row.getStatus());
        // 缓存命中与创建时间不由本类设置（随写入场景不同）
        assertNull(row.getCacheHit());
        assertFalse(row.getTokenCount() < 0);
    }

    /** 产物位置夹具 */
    private ObjectRef ref() {
        return new ObjectRef(9L, StorageType.MINIO, "artifacts", "embed/set.json");
    }
}
