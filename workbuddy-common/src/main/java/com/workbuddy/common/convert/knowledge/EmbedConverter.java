package com.workbuddy.common.convert.knowledge;

import com.workbuddy.common.config.BaseInfoConvertConfig;
import com.workbuddy.common.domain.embed.EmbeddingRecord;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.embed.EmbedRecordItemVO;
import com.workbuddy.common.enums.task.RowStatus;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * 向量转换：记录行 ↔ 条目 VO，以及向量产物 → 落库行。只搬字段，不查库、不算统计。
 */
@Mapper(config = BaseInfoConvertConfig.class)
public interface EmbedConverter {

    /**
     * 记录行转记录条目 VO 列表。
     *
     * @param records 向量记录列表
     * @return 记录条目 VO 列表
     */
    List<EmbedRecordItemVO> toRecordItemVOs(List<WbEmbeddingRecord> records);

    /**
     * 单条记录行转记录条目 VO。
     *
     * @param record 向量记录行
     * @return 记录条目 VO
     */
    EmbedRecordItemVO toRecordItemVO(WbEmbeddingRecord record);

    /**
     * 向量集合落库行：产物引用列取自对象存储位置。
     *
     * @param fileResultId    文件结果 ID
     * @param set             向量集合产物
     * @param strategyVersion 向量化策略完整版本
     * @param ref             产物对象位置
     * @return 向量集合行（未保存）
     */
    default WbEmbeddingSet setRow(Long fileResultId, EmbeddingSet set, String strategyVersion,
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
     * 向量记录落库行：只搬字段。
     *
     * @param embeddingSetId 向量集合行 ID
     * @param record         向量记录产物
     * @return 向量记录行（未保存）
     */
    default WbEmbeddingRecord recordRow(Long embeddingSetId, EmbeddingRecord record) {
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
