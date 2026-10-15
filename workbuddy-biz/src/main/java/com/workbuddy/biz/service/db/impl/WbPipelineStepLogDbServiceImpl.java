package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workbuddy.biz.mapper.WbPipelineStepLogMapper;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 子步骤记录数据访问服务实现。
 *
 * @author cxxl
 */
@Service
public class WbPipelineStepLogDbServiceImpl extends InfraDbServiceImpl<WbPipelineStepLogMapper, WbPipelineStepLog>
        implements WbPipelineStepLogDbService {

    @Override
    public List<WbPipelineStepLog> listByTaskId(Long taskId) {
        LambdaQueryWrapper<WbPipelineStepLog> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineStepLog::getTaskId, taskId)
                .orderByAsc(WbPipelineStepLog::getId);
        return list(queryWrapper);
    }

    @Override
    public List<WbPipelineStepLog> listByTaskIds(List<Long> taskIds) {
        if (NullUtil.isNull(taskIds) || taskIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<WbPipelineStepLog> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(WbPipelineStepLog::getTaskId, taskIds)
                .orderByAsc(WbPipelineStepLog::getId);
        return list(queryWrapper);
    }
}
