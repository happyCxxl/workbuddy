package com.workbuddy.common.domain.task;

import java.time.LocalDateTime;

/**
 * 子步骤字段契约（StepLogInfo / wb_knowledge_pipeline_step_log 实体 / StepLogVO 同构十一字段）：
 * 统一拷贝入口，各层免重复映射。
 */
public interface StepLogFields {

    String getStepName();

    void setStepName(String stepName);

    String getStatus();

    void setStatus(String status);

    String getCapabilityVersion();

    void setCapabilityVersion(String capabilityVersion);

    LocalDateTime getStartedAt();

    void setStartedAt(LocalDateTime startedAt);

    LocalDateTime getFinishedAt();

    void setFinishedAt(LocalDateTime finishedAt);

    Integer getDuration();

    void setDuration(Integer duration);

    Integer getWarningCount();

    void setWarningCount(Integer warningCount);

    Integer getMatchedCount();

    void setMatchedCount(Integer matchedCount);

    Integer getChangedCount();

    void setChangedCount(Integer changedCount);

    Integer getAvgLen();

    void setAvgLen(Integer avgLen);

    String getError();

    void setError(String error);

    /** 同构字段逐一拷贝（纯映射）。 */
    default void copyFrom(StepLogFields source) {
        setStepName(source.getStepName());
        setStatus(source.getStatus());
        setCapabilityVersion(source.getCapabilityVersion());
        setStartedAt(source.getStartedAt());
        setFinishedAt(source.getFinishedAt());
        setDuration(source.getDuration());
        setWarningCount(source.getWarningCount());
        setMatchedCount(source.getMatchedCount());
        setChangedCount(source.getChangedCount());
        setAvgLen(source.getAvgLen());
        setError(source.getError());
    }
}
