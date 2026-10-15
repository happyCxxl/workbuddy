package com.workbuddy.biz.service.db;

import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.List;

/**
 * 向量产物集合数据访问服务。
 *
 * @author cxxl
 */
public interface WbEmbeddingSetDbService extends InfraDbService<WbEmbeddingSet> {

    /**
     * 取文件结果最新的向量集合（无 → null；详情摘要数据源）。
     */
    WbEmbeddingSet getLatestByFileResultId(Long fileResultId);

    /**
     * 查单文件全部向量集合（执行树统计按 artifactId 匹配用），id 升序。
     */
    List<WbEmbeddingSet> listByFileResultId(Long fileResultId);

    /**
     * 取文件结果 + 同策略的历史成功集合（createTime 新→旧，上限 limit；账本复用回溯候选数据源）。
     */
    List<WbEmbeddingSet> listHistoryByFileResultIdAndStrategyVersion(Long fileResultId, String strategyVersion, int limit);

    /**
     * 按产物指纹精确取向量集合（历史任务详情数据源；无 → null），id 降序取最新。
     */
    WbEmbeddingSet getByArtifactId(String artifactId);

    /**
     * 批量查多文件全部向量集合（组合枚举数据源），id 升序。
     */
    List<WbEmbeddingSet> listByFileResultIds(List<Long> fileResultIds);
}
