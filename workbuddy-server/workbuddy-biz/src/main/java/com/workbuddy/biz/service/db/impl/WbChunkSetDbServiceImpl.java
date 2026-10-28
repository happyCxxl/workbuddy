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
 */
@Service
public class WbChunkSetDbServiceImpl extends InfraDbServiceImpl<WbChunkSetMapper, WbChunkSet>
        implements WbChunkSetDbService {

    /**
     * 按产物 ID 取切片集合。
     *
     * @param artifactId 产物 ID
     * @return 切片集合；没有则为 null
     */
    @Override
    public WbChunkSet getByArtifactId(String artifactId) {
        LambdaQueryWrapper<WbChunkSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbChunkSet::getArtifactId, artifactId)
                .orderByDesc(WbChunkSet::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    /**
     * 取该文件最新一条切片集合。
     *
     * @param fileResultId 文件结果 ID
     * @return 最新切片集合；没有则为 null
     */
    @Override
    public WbChunkSet getLatestByFileResultId(Long fileResultId) {
        LambdaQueryWrapper<WbChunkSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbChunkSet::getFileResultId, fileResultId)
                .orderByDesc(WbChunkSet::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    /**
     * 取该文件的全部切片集合，按 ID 升序。
     *
     * @param fileResultId 文件结果 ID
     * @return 切片集合列表
     */
    @Override
    public List<WbChunkSet> listByFileResultId(Long fileResultId) {
        LambdaQueryWrapper<WbChunkSet> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbChunkSet::getFileResultId, fileResultId)
                .orderByAsc(WbChunkSet::getId);
        return list(queryWrapper);
    }

    /**
     * 批量取切片集合，按 ID 升序。
     *
     * @param fileResultIds 文件结果 ID 列表
     * @return 命中的切片集合；入参为空时返回空列表
     */
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
