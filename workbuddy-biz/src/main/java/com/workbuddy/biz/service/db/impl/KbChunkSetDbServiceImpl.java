package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workbuddy.biz.mapper.KbChunkSetMapper;
import com.workbuddy.biz.service.db.KbChunkSetDbService;
import com.workbuddy.common.domain.entity.KbChunkSet;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 切片产物集合数据访问服务实现。
 *
 * @author cxxl
 */
@Service
public class KbChunkSetDbServiceImpl extends InfraDbServiceImpl<KbChunkSetMapper, KbChunkSet>
        implements KbChunkSetDbService {

    @Override
    public KbChunkSet getByArtifactId(String artifactId) {
        LambdaQueryWrapper<KbChunkSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbChunkSet::getArtifactId, artifactId)
                .orderByDesc(KbChunkSet::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public KbChunkSet getLatestByFileResultId(Long fileResultId) {
        LambdaQueryWrapper<KbChunkSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbChunkSet::getFileResultId, fileResultId)
                .orderByDesc(KbChunkSet::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public List<KbChunkSet> listByFileResultId(Long fileResultId) {
        LambdaQueryWrapper<KbChunkSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbChunkSet::getFileResultId, fileResultId)
                .orderByAsc(KbChunkSet::getId);
        return list(queryWrapper);
    }

    @Override
    public List<KbChunkSet> listByFileResultIds(List<Long> fileResultIds) {
        if (NullUtil.isNull(fileResultIds) || fileResultIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<KbChunkSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(KbChunkSet::getFileResultId, fileResultIds)
                .orderByAsc(KbChunkSet::getId);
        return list(queryWrapper);
    }
}
