package com.workbuddy.common.dto.response.task;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 环节详情视图公共字段：文件结果与任务状态摘要、产物引用、子步骤列表。
 * 各环节详情 VO 继承本类并追加环节专属统计/列表字段。
 *
 * @param <T> 环节专属统计 VO 类型
 */
@Data
public class StageDetailVO<T extends StageStatsVO> implements TaskStatusVO {

    /** 文件结果 ID */
    private Long fileResultId;

    /** 任务 ID（wb_knowledge_pipeline_task.id；无任务时为空） */
    private Long taskId;

    /** 环节 */
    private String stage;

    /** 任务状态（PipelineTaskStatus 枚举名） */
    private String status;

    /** 任务错误码（PipelineTaskErrorCode 枚举名） */
    private String errorCode;

    /** 任务错误信息 */
    private String errorMsg;

    /** 任务开始时间 */
    private LocalDateTime startedAt;

    /** 任务结束时间 */
    private LocalDateTime finishedAt;

    /** 产物引用（sha256 寻址 key；失败无产物时为空） */
    private String artifactId;

    /** 产物内容指纹 */
    private String contentHash;

    /** 能力快照（JSON 文本） */
    private String capabilitySnapshot;

    /** 子步骤列表 */
    private List<StepLogVO> steps;

    /**
     * 环节统计（读产物现算）：与执行树节点的 {@code stats} 同一份口径。
     *
     * <p>具体类型由各环节详情 VO 绑定；值为 null 的字段不出现。产物不可读时为 null（此时不陈述结论）。
     */
    private T stageStats;

    /**
     * 环节摘要行文案：与执行树节点的 {@code stageSummary} 同一份口径。
     *
     * <p>产物不可读导致统计缺失时为空 —— 统计没到手就不陈述结论。
     */
    private String stageSummary;
}
