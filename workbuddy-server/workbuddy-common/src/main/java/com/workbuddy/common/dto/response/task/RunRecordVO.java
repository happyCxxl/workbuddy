package com.workbuddy.common.dto.response.task;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 运行记录 VO：文件某环节的一次任务运行（运行历史列表数据源）。
 */
@Data
public class RunRecordVO {

    /** 任务 ID */
    private Long taskId;

    /** 环节（PipelineStage 枚举名） */
    private String stage;

    /** 任务状态（PipelineTaskStatus 枚举名） */
    private String status;

    /** 任务错误码（失败时非空） */
    private String errorCode;

    /** 任务错误信息（失败时非空） */
    private String errorMsg;

    /** 生效策略版本（PREPROCESS/CHUNK，从任务策略快照推导；解析/组装为空） */
    private String strategyVersion;

    /** 该次运行产出的产物 ID（wb_knowledge_pipeline_product.id；历史任务为空，前端"以此产物触发下游"数据源） */
    private Long productId;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;
}
