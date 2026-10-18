package com.workbuddy.common.dto.response.task;

import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import com.workbuddy.common.domain.task.StepLogFields;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 环节子步骤 VO（四环节通用：解析/组装/预处理/切片步骤日志同构）。
 */
@Data
public class StepLogVO implements StepLogFields {

    /** 子步骤名 */
    private String stepName;

    /** 步骤状态（SUCCESS/FAILED） */
    private String status;

    /** 能力/模型版本 */
    private String capabilityVersion;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;

    /** 耗时（毫秒） */
    private Integer duration;

    /** 告警数 */
    private Integer warningCount;

    /** 命中数（规则命中次数；解析环节为 0） */
    private Integer matchedCount;

    /** 变更数（实际改写次数；解析环节为 0） */
    private Integer changedCount;

    /** 平均长度（切片子步骤统计；其他环节为 0） */
    private Integer avgLen;

    /** 错误信息 */
    private String error;

    /** 实体 → VO（公共字段拷贝）。 */
    public static StepLogVO of(WbPipelineStepLog step) {
        StepLogVO vo = new StepLogVO();
        vo.copyFrom(step);
        return vo;
    }
}
