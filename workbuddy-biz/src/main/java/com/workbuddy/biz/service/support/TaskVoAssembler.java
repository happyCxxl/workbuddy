package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.dto.response.task.StageStatusVO;
import org.springframework.stereotype.Component;

/**
 * 任务机制 VO 组装器（跨环节共用）：处理链任务实体 → 任务机制对外 VO 的纯映射。
 */
@Component
public class TaskVoAssembler {

    /**
     * 任务 → 环节状态 VO（任务属于哪个 stage 就挂在哪个 stage 下）。
     *
     * @param task 处理链任务实体
     * @return 环节状态 VO
     */
    public StageStatusVO toStageStatusVO(WbPipelineTask task) {
        StageStatusVO vo = new StageStatusVO();
        vo.applyFrom(task);
        return vo;
    }
}
