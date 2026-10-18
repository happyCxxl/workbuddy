package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.dto.response.embed.EmbedRecordItemVO;
import com.workbuddy.common.dto.response.embed.EmbedSummaryVO;
import com.workbuddy.common.enums.embed.EmbedRecordStatus;
import com.workbuddy.common.utils.NullUtil;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量化详情 VO 组装器（纯映射，不查库）：集合行 + 记录行 → 集合摘要/记录条目视图。
 * 集合与记录由 Service 查好传入；任务/子步骤字段组装不归本类。
 * 摘要三态统计口径：SUCCESS/FAILED/SKIPPED 计数，CACHED 不参与（复用命中单独展示 cachedCount）。
 */
@Component
public class EmbedVoAssembler {

    /** 集合摘要：基础字段直搬 + 记录三态统计（SUCCESS/FAILED/SKIPPED）。 */
    public EmbedSummaryVO toSummary(WbEmbeddingSet set, List<WbEmbeddingRecord> records) {
        EmbedSummaryVO vo = new EmbedSummaryVO();
        vo.setEmbeddingSetId(set.getEmbeddingSetId());
        vo.setStrategyVersion(set.getStrategyVersion());
        vo.setModel(set.getModel());
        vo.setDimension(set.getDimension());
        vo.setMetric(set.getMetric());
        vo.setNormalized(set.getNormalized());
        vo.setRecordCount(set.getRecordCount());
        vo.setCachedCount(set.getCachedCount());
        int success = 0;
        int failed = 0;
        int skipped = 0;
        for (WbEmbeddingRecord record : records) {
            EmbedRecordStatus status = EmbedRecordStatus.of(record.getStatus());
            if (NullUtil.isNull(status)) {
                continue;
            }
            switch (status) {
                case SUCCESS -> success++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
                default -> {
                    // CACHED 不参与三态统计（复用命中单独展示 cachedCount）
                }
            }
        }
        vo.setSuccessCount(success);
        vo.setFailedCount(failed);
        vo.setSkippedCount(skipped);
        return vo;
    }

    /** 记录行 → 记录条目 VO 列表。 */
    public List<EmbedRecordItemVO> toRecordItemVOs(List<WbEmbeddingRecord> records) {
        return records.stream().map(this::toRecordItemVO).toList();
    }

    private EmbedRecordItemVO toRecordItemVO(WbEmbeddingRecord record) {
        EmbedRecordItemVO vo = new EmbedRecordItemVO();
        vo.setEmbeddingId(record.getEmbeddingId());
        vo.setChunkId(record.getChunkId());
        vo.setContentType(record.getContentType());
        vo.setInputText(record.getInputText());
        vo.setTokenCount(record.getTokenCount());
        vo.setRequestId(record.getRequestId());
        vo.setStatus(record.getStatus());
        vo.setCacheHit(record.getCacheHit());
        return vo;
    }
}
