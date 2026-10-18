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

    @Override
    public WbEmbeddingSet getLatestByFileResultId(Long fileResultId) {
        LambdaQueryWrapper<WbEmbeddingSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbEmbeddingSet::getFileResultId, fileResultId)
                .orderByDesc(WbEmbeddingSet::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public List<WbEmbeddingSet> listByFileResultId(Long fileResultId) {
        LambdaQueryWrapper<WbEmbeddingSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbEmbeddingSet::getFileResultId, fileResultId)
                .orderByAsc(WbEmbeddingSet::getId);
        return list(queryWrapper);
    }

    @Override
    public List<WbEmbeddingSet> listHistoryByFileResultIdAndStrategyVersion(Long fileResultId,
                                                                            String strategyVersion, int limit) {
        LambdaQueryWrapper<WbEmbeddingSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbEmbeddingSet::getFileResultId, fileResultId)
                .eq(WbEmbeddingSet::getStrategyVersion, strategyVersion)
                .eq(WbEmbeddingSet::getStatus, RowStatus.ACTIVE.name())
                .orderByDesc(WbEmbeddingSet::getId)
                .last("LIMIT " + Math.max(1, limit));
        return list(queryWrapper);
    }

    @Override
    public WbEmbeddingSet getByArtifactId(String artifactId) {
        LambdaQueryWrapper<WbEmbeddingSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbEmbeddingSet::getArtifactId, artifactId)
                .orderByDesc(WbEmbeddingSet::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public List<WbEmbeddingSet> listByFileResultIds(List<Long> fileResultIds) {
        if (NullUtil.isNull(fileResultIds) || fileResultIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<WbEmbeddingSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(WbEmbeddingSet::getFileResultId, fileResultIds)
                .orderByAsc(WbEmbeddingSet::getId);
        return list(queryWrapper);
    }
}
