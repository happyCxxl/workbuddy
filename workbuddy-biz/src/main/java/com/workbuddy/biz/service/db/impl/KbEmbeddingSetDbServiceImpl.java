package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workbuddy.biz.mapper.KbEmbeddingSetMapper;
import com.workbuddy.biz.service.db.KbEmbeddingSetDbService;
import com.workbuddy.common.domain.entity.KbEmbeddingSet;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量产物集合数据访问服务实现。
 *
 * @author cxxl
 */
@Service
public class KbEmbeddingSetDbServiceImpl extends InfraDbServiceImpl<KbEmbeddingSetMapper, KbEmbeddingSet>
        implements KbEmbeddingSetDbService {

    @Override
    public KbEmbeddingSet getLatestByFileResultId(Long fileResultId) {
        LambdaQueryWrapper<KbEmbeddingSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbEmbeddingSet::getFileResultId, fileResultId)
                .orderByDesc(KbEmbeddingSet::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public List<KbEmbeddingSet> listByFileResultId(Long fileResultId) {
        LambdaQueryWrapper<KbEmbeddingSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbEmbeddingSet::getFileResultId, fileResultId)
                .orderByAsc(KbEmbeddingSet::getId);
        return list(queryWrapper);
    }

    @Override
    public List<KbEmbeddingSet> listHistoryByFileResultIdAndStrategyVersion(Long fileResultId,
                                                                            String strategyVersion, int limit) {
        LambdaQueryWrapper<KbEmbeddingSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbEmbeddingSet::getFileResultId, fileResultId)
                .eq(KbEmbeddingSet::getStrategyVersion, strategyVersion)
                .eq(KbEmbeddingSet::getStatus, RowStatus.ACTIVE.name())
                .orderByDesc(KbEmbeddingSet::getId)
                .last("LIMIT " + Math.max(1, limit));
        return list(queryWrapper);
    }

    @Override
    public KbEmbeddingSet getByArtifactId(String artifactId) {
        LambdaQueryWrapper<KbEmbeddingSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbEmbeddingSet::getArtifactId, artifactId)
                .orderByDesc(KbEmbeddingSet::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public List<KbEmbeddingSet> listByFileResultIds(List<Long> fileResultIds) {
        if (NullUtil.isNull(fileResultIds) || fileResultIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<KbEmbeddingSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(KbEmbeddingSet::getFileResultId, fileResultIds)
                .orderByAsc(KbEmbeddingSet::getId);
        return list(queryWrapper);
    }
}
