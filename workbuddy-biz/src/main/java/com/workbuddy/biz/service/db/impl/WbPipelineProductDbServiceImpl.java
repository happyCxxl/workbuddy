package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workbuddy.biz.mapper.WbPipelineProductMapper;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 阶段产物数据访问服务实现。
 *
 * @author cxxl
 */
@Service
public class WbPipelineProductDbServiceImpl extends InfraDbServiceImpl<WbPipelineProductMapper, WbPipelineProduct>
        implements WbPipelineProductDbService {

    @Override
    public WbPipelineProduct getByTaskId(Long taskId) {
        LambdaQueryWrapper<WbPipelineProduct> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineProduct::getTaskId, taskId)
                .orderByDesc(WbPipelineProduct::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public WbPipelineProduct getByFileResultIdAndStage(Long fileResultId, String stage) {
        LambdaQueryWrapper<WbPipelineProduct> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineProduct::getFileResultId, fileResultId)
                .eq(WbPipelineProduct::getStage, stage)
                .orderByDesc(WbPipelineProduct::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public List<WbPipelineProduct> listByFileResultId(Long fileResultId) {
        LambdaQueryWrapper<WbPipelineProduct> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineProduct::getFileResultId, fileResultId)
                .orderByAsc(WbPipelineProduct::getId);
        return list(queryWrapper);
    }
}
