package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workbuddy.biz.mapper.WbChunkSetMapper;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.common.domain.entity.WbChunkSet;
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
public class WbChunkSetDbServiceImpl extends InfraDbServiceImpl<WbChunkSetMapper, WbChunkSet>
        implements WbChunkSetDbService {

    @Override
    public WbChunkSet getByArtifactId(String artifactId) {
        LambdaQueryWrapper<WbChunkSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbChunkSet::getArtifactId, artifactId)
                .orderByDesc(WbChunkSet::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public WbChunkSet getLatestByFileResultId(Long fileResultId) {
        LambdaQueryWrapper<WbChunkSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbChunkSet::getFileResultId, fileResultId)
                .orderByDesc(WbChunkSet::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public List<WbChunkSet> listByFileResultId(Long fileResultId) {
        LambdaQueryWrapper<WbChunkSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbChunkSet::getFileResultId, fileResultId)
                .orderByAsc(WbChunkSet::getId);
        return list(queryWrapper);
    }

    @Override
    public List<WbChunkSet> listByFileResultIds(List<Long> fileResultIds) {
        if (NullUtil.isNull(fileResultIds) || fileResultIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<WbChunkSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(WbChunkSet::getFileResultId, fileResultIds)
                .orderByAsc(WbChunkSet::getId);
        return list(queryWrapper);
    }
}
