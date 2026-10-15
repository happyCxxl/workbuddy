package com.workbuddy.biz.service.db;

import com.workbuddy.common.domain.entity.KbPipelineStepLog;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.List;

/**
 * 子步骤记录数据访问服务（kb_pipeline_step_log）。
 *
 * @author cxxl
 */
public interface KbPipelineStepLogDbService extends InfraDbService<KbPipelineStepLog> {

    /**
     * 按任务查子步骤列表（id 升序 = 执行顺序）。
     *
     * @param taskId 任务 ID（kb_pipeline_task.id）
     * @return 子步骤列表
     */
    List<KbPipelineStepLog> listByTaskId(Long taskId);

    /**
     * 批量按任务查子步骤（id 升序）。
     *
     * @param taskIds 任务 ID 列表（为空返回空列表，不查库）
     * @return 子步骤列表
     */
    List<KbPipelineStepLog> listByTaskIds(List<Long> taskIds);
}
