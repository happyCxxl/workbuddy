package com.workbuddy.biz.service.db;

import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.List;

/**
 * 子步骤记录数据访问服务（wb_knowledge_pipeline_step_log）。
 *
 * @author cxxl
 */
public interface WbPipelineStepLogDbService extends InfraDbService<WbPipelineStepLog> {

    /**
     * 按任务查子步骤列表（id 升序 = 执行顺序）。
     *
     * @param taskId 任务 ID（wb_knowledge_pipeline_task.id）
     * @return 子步骤列表
     */
    List<WbPipelineStepLog> listByTaskId(Long taskId);

    /**
     * 批量按任务查子步骤（id 升序）。
     *
     * @param taskIds 任务 ID 列表（为空返回空列表，不查库）
     * @return 子步骤列表
     */
    List<WbPipelineStepLog> listByTaskIds(List<Long> taskIds);
}
