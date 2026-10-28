package com.workbuddy.common.dto.response.task;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 环节状态 VO：文件列表行内各环节最新任务的状态。
 * 无任务时列表不含该环节条目。
 */
@Data
public class StageStatusVO implements TaskStatusVO {

    /** 环节（PipelineStage 枚举名，如 PARSE） */
    private String stage;

    /** 该环节最新任务 ID */
    private Long taskId;

    /** 任务状态（PipelineTaskStatus 枚举名） */
    private String status;

    /** 任务错误码（失败时非空） */
    private String errorCode;

    /** 任务错误信息（失败时非空） */
    private String errorMsg;

    /** 任务开始时间 */
    private LocalDateTime startedAt;

    /** 任务结束时间 */
    private LocalDateTime finishedAt;
}
