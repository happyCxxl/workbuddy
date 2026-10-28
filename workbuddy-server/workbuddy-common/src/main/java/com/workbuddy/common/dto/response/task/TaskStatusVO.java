package com.workbuddy.common.dto.response.task;

import com.workbuddy.common.domain.entity.WbPipelineTask;

import java.time.LocalDateTime;

/**
 * 任务状态视图契约（StageStatusVO/ParseDetailVO 等详情视图通用字段）：
 * 任务摘要七字段的统一映射入口，视图侧免重复赋值。
 */
public interface TaskStatusVO {

    void setStage(String stage);

    void setTaskId(Long taskId);

    void setStatus(String status);

    void setErrorCode(String errorCode);

    void setErrorMsg(String errorMsg);

    void setStartedAt(LocalDateTime startedAt);

    void setFinishedAt(LocalDateTime finishedAt);

    /** 任务实体 → 视图通用字段（纯映射）。 */
    default void applyFrom(WbPipelineTask task) {
        setStage(task.getStage());
        setTaskId(task.getId());
        setStatus(task.getStatus());
        setErrorCode(task.getErrorCode());
        setErrorMsg(task.getErrorMsg());
        setStartedAt(task.getStartedAt());
        setFinishedAt(task.getFinishedAt());
    }
}
