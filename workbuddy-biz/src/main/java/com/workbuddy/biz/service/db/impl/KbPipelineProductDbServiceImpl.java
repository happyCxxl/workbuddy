package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workbuddy.biz.mapper.KbPipelineProductMapper;
import com.workbuddy.biz.service.db.KbPipelineProductDbService;
import com.workbuddy.common.domain.entity.KbPipelineProduct;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 阶段产物数据访问服务实现。
 *
 * @author cxxl
 */
@Service
public class KbPipelineProductDbServiceImpl extends InfraDbServiceImpl<KbPipelineProductMapper, KbPipelineProduct>
        implements KbPipelineProductDbService {

    @Override
    public KbPipelineProduct getByTaskId(Long taskId) {
        LambdaQueryWrapper<KbPipelineProduct> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbPipelineProduct::getTaskId, taskId)
                .orderByDesc(KbPipelineProduct::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public KbPipelineProduct getByFileResultIdAndStage(Long fileResultId, String stage) {
        LambdaQueryWrapper<KbPipelineProduct> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbPipelineProduct::getFileResultId, fileResultId)
                .eq(KbPipelineProduct::getStage, stage)
                .orderByDesc(KbPipelineProduct::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public List<KbPipelineProduct> listByFileResultId(Long fileResultId) {
        LambdaQueryWrapper<KbPipelineProduct> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbPipelineProduct::getFileResultId, fileResultId)
                .orderByAsc(KbPipelineProduct::getId);
        return list(queryWrapper);
    }
}
