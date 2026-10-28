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
 */
@Service
public class WbPipelineStepLogDbServiceImpl extends InfraDbServiceImpl<WbPipelineStepLogMapper, WbPipelineStepLog>
        implements WbPipelineStepLogDbService {

    /**
     * 取某任务的步骤日志。
     *
     * @param taskId 任务 ID
     * @return 步骤日志列表，按步骤号升序
     */
    @Override
    public List<WbPipelineStepLog> listByTaskId(Long taskId) {
        LambdaQueryWrapper<WbPipelineStepLog> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineStepLog::getTaskId, taskId)
                .orderByAsc(WbPipelineStepLog::getId);
        return list(queryWrapper);
    }

    /**
     * 批量取多个任务的步骤日志。
     *
     * @param taskIds 任务 ID 列表
     * @return 步骤日志列表；入参为空时返回空列表
     */
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
