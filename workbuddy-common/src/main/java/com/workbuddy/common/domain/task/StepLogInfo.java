package com.workbuddy.common.domain.task;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 子步骤记录信息：ParsePipeline 产出，由 biz 落 kb_pipeline_step_log。
 *
 * @author cxxl
 */
@Data
public class StepLogInfo implements StepLogFields {

    /** 子步骤名（文件级路由/原生解析/质量检查） */
    private String stepName;

    /** 步骤状态（StepStatus 枚举名） */
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

    /** 命中数（规则命中次数；解析环节不适用可空） */
    private Integer matchedCount;

    /** 变更数（实际改写次数；解析环节不适用可空） */
    private Integer changedCount;

    /** 平均长度（切片子步骤统计；其他环节为 0） */
    private Integer avgLen;

    /** 错误信息 */
    private String error;
}
