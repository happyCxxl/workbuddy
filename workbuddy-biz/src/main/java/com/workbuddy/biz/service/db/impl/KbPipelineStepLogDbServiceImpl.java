package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workbuddy.biz.mapper.KbPipelineStepLogMapper;
import com.workbuddy.biz.service.db.KbPipelineStepLogDbService;
import com.workbuddy.common.domain.entity.KbPipelineStepLog;
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
public class KbPipelineStepLogDbServiceImpl extends InfraDbServiceImpl<KbPipelineStepLogMapper, KbPipelineStepLog>
        implements KbPipelineStepLogDbService {

    @Override
    public List<KbPipelineStepLog> listByTaskId(Long taskId) {
        LambdaQueryWrapper<KbPipelineStepLog> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbPipelineStepLog::getTaskId, taskId)
                .orderByAsc(KbPipelineStepLog::getId);
        return list(queryWrapper);
    }

    @Override
    public List<KbPipelineStepLog> listByTaskIds(List<Long> taskIds) {
        if (NullUtil.isNull(taskIds) || taskIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<KbPipelineStepLog> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(KbPipelineStepLog::getTaskId, taskIds)
                .orderByAsc(KbPipelineStepLog::getId);
        return list(queryWrapper);
    }
}
