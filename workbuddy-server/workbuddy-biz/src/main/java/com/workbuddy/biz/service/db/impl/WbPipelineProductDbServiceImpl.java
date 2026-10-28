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
 */
@Service
public class WbPipelineProductDbServiceImpl extends InfraDbServiceImpl<WbPipelineProductMapper, WbPipelineProduct>
        implements WbPipelineProductDbService {

    /**
     * 按任务取产物。
     *
     * @param taskId 任务 ID
     * @return 产物行；没有则为 null
     */
    @Override
    public WbPipelineProduct getByTaskId(Long taskId) {
        LambdaQueryWrapper<WbPipelineProduct> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineProduct::getTaskId, taskId)
                .orderByDesc(WbPipelineProduct::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    /**
     * 按文件结果与环节取产物。
     *
     * @param fileResultId 文件结果 ID
     * @param stage        环节名
     * @return 产物行；没有则为 null
     */
    @Override
    public WbPipelineProduct getByFileResultIdAndStage(Long fileResultId, String stage) {
        LambdaQueryWrapper<WbPipelineProduct> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineProduct::getFileResultId, fileResultId)
                .eq(WbPipelineProduct::getStage, stage)
                .orderByDesc(WbPipelineProduct::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    /**
     * 取某文件的全部产物，按 ID 升序。
     *
     * @param fileResultId 文件结果 ID
     * @return 产物列表
     */
    @Override
    public List<WbPipelineProduct> listByFileResultId(Long fileResultId) {
        LambdaQueryWrapper<WbPipelineProduct> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineProduct::getFileResultId, fileResultId)
                .orderByAsc(WbPipelineProduct::getId);
        return list(queryWrapper);
    }
}
