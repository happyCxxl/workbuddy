package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workbuddy.biz.mapper.WbEmbeddingSetMapper;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量产物集合数据访问服务实现。
 */
@Service
public class WbEmbeddingSetDbServiceImpl extends InfraDbServiceImpl<WbEmbeddingSetMapper, WbEmbeddingSet>
        implements WbEmbeddingSetDbService {

    /**
     * 取该文件最新一条向量集合。
     *
     * @param knowledgeFileId 知识库文件 ID
     * @return 最新向量集合；没有则为 null
     */
    @Override
    public WbEmbeddingSet getLatestByFileResultId(Long knowledgeFileId) {
        LambdaQueryWrapper<WbEmbeddingSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbEmbeddingSet::getFileResultId, knowledgeFileId)
                .orderByDesc(WbEmbeddingSet::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    /**
     * 取该文件的全部向量集合，按 ID 升序。
     *
     * @param knowledgeFileId 知识库文件 ID
     * @return 向量集合列表
     */
    @Override
    public List<WbEmbeddingSet> listByFileResultId(Long knowledgeFileId) {
        LambdaQueryWrapper<WbEmbeddingSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbEmbeddingSet::getFileResultId, knowledgeFileId)
                .orderByAsc(WbEmbeddingSet::getId);
        return list(queryWrapper);
    }

    /**
     * 取同策略的历史成功集合，按 ID 降序限制条数。
     *
     * @param knowledgeFileId    知识库文件 ID
     * @param strategyVersion 向量策略版本
     * @param limit           条数上限（小于 1 按 1 处理）
     * @return 命中的向量集合列表
     */
    @Override
    public List<WbEmbeddingSet> listHistoryByFileResultIdAndStrategyVersion(Long knowledgeFileId,
                                                                            String strategyVersion, int limit) {
        LambdaQueryWrapper<WbEmbeddingSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbEmbeddingSet::getFileResultId, knowledgeFileId)
                .eq(WbEmbeddingSet::getStrategyVersion, strategyVersion)
                .eq(WbEmbeddingSet::getStatus, RowStatus.ACTIVE.name())
                .orderByDesc(WbEmbeddingSet::getId)
                .last("LIMIT " + Math.max(1, limit));
        return list(queryWrapper);
    }

    /**
     * 按产物指纹取向量集合。
     *
     * @param artifactId 产物 ID
     * @return 命中的向量集合；没有则为 null
     */
    @Override
    public WbEmbeddingSet getByArtifactId(String artifactId) {
        LambdaQueryWrapper<WbEmbeddingSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbEmbeddingSet::getArtifactId, artifactId)
                .orderByDesc(WbEmbeddingSet::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    /**
     * 批量取向量集合，按 ID 升序。
     *
     * @param knowledgeFileIds 知识库文件 ID 列表
     * @return 命中的向量集合；入参为空时返回空列表
     */
    @Override
    public List<WbEmbeddingSet> listByKnowledgeFileIds(List<Long> knowledgeFileIds) {
        if (NullUtil.isNull(knowledgeFileIds) || knowledgeFileIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<WbEmbeddingSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(WbEmbeddingSet::getFileResultId, knowledgeFileIds)
                .orderByAsc(WbEmbeddingSet::getId);
        return list(queryWrapper);
    }
}
