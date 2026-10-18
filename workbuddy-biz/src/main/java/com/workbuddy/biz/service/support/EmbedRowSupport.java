package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.embed.EmbeddingRecord;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.task.RowStatus;

/**
 * 向量化落库行装配：集合行与记录行的字段口径集中在这里。
 *
 * <p>主键、缓存命中、创建时间不由本类设置（这三项随写入场景不同）。
 */
public final class EmbedRowSupport {

    private EmbedRowSupport() {
    }

    /**
     * 集合账本行（文件 + 集合 + 产物引用与对象位置，状态 ACTIVE）。
     *
     * @param fileResultId    文件结果 ID
     * @param set             向量化集合（环节产物）
     * @param strategyVersion 生效策略版本（种子注入用内置版本口径）
     * @param ref             产物对象位置
     */
    public static WbEmbeddingSet setRow(Long fileResultId, EmbeddingSet set, String strategyVersion,
                                        ObjectRef ref) {
        WbEmbeddingSet row = new WbEmbeddingSet();
        row.setFileResultId(fileResultId);
        row.setChunkSetRef(set.getChunkSetRef());
        row.setEmbeddingSetId(set.getEmbeddingSetId());
        row.setStrategyVersion(strategyVersion);
        row.setModel(set.getModel());
        row.setDimension(set.getDimension());
        row.setMetric(set.getMetric());
        row.setNormalized(set.isNormalized());
        row.setRecordCount(set.getRecordCount());
        row.setCachedCount(set.getCachedCount());
        row.setStatus(RowStatus.ACTIVE.name());
        row.setArtifactId(ref.objectKey());
        row.setStorageType(ref.storageType().getCode());
        row.setStorageSourceId(ref.sourceId());
        row.setBucket(ref.bucket());
        return row;
    }

    /**
     * 记录行（片定位 + 输入文本口径 + 请求号，状态原样带入）。
     *
     * @param embeddingSetId 所属集合行 ID
     * @param record         向量化记录（环节产物元素）
     */
    public static WbEmbeddingRecord recordRow(Long embeddingSetId, EmbeddingRecord record) {
        WbEmbeddingRecord row = new WbEmbeddingRecord();
        row.setEmbeddingSetId(embeddingSetId);
        row.setEmbeddingId(record.getEmbeddingId());
        row.setChunkId(record.getChunkId());
        row.setContentType(record.getContentType());
        row.setParentChunkId(record.getParentChunkId());
        row.setInputText(record.getInputText());
        row.setInputTextHash(record.getInputTextHash());
        row.setTokenCount(record.getTokenCount());
        row.setRequestId(record.getRequestId());
        row.setStatus(record.getStatus());
        return row;
    }
}
