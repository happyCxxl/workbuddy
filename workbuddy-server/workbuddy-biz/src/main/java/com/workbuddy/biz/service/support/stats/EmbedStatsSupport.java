package com.workbuddy.biz.service.support.stats;

import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.dto.response.embed.EmbedSummaryVO;
import com.workbuddy.common.enums.embed.EmbedRecordStatus;
import com.workbuddy.common.utils.NullUtil;

import java.util.List;

/**
 * 向量化详情统计：集合基础字段 + 记录三态计数（SUCCESS/FAILED/SKIPPED）。
 *
 * <p>纯读取；CACHED 不参与三态统计（复用命中由集合行的 cachedCount 单独表达）。
 */
public final class EmbedStatsSupport {

    private EmbedStatsSupport() {
    }

    /**
     * 集合摘要：基础字段直接取集合行，三态计数按记录状态现算。
     *
     * @param set     向量集合行
     * @param records 向量记录列表
     * @return 集合摘要视图
     */
    public static EmbedSummaryVO summaryOf(WbEmbeddingSet set, List<WbEmbeddingRecord> records) {
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
        for (WbEmbeddingRecord record : ObjectUtil.defaultIfNull(records, List.<WbEmbeddingRecord>of())) {
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
}
