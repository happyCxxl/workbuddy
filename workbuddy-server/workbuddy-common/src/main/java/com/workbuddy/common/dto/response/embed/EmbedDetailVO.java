package com.workbuddy.common.dto.response.embed;

import com.workbuddy.common.dto.response.task.StepLogVO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 向量化详情 VO：任务状态 + 子步骤 + 集合摘要/记录（wb_knowledge_embedding_set/wb_knowledge_embedding_record 数据源）+ 产物引用。
 * 向量本体不下发；集合按 task.productId 精确取该次运行的产物（历史任务同样可展示自己的集合）。
 */
@Data
public class EmbedDetailVO {

    /** 文件结果 ID */
    private Long fileResultId;

    /** 任务 ID（无任务时为空） */
    private Long taskId;

    /** 环节（EMBED） */
    private String stage;

    /** 任务状态（PipelineTaskStatus 枚举名） */
    private String taskStatus;

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

    /** 能力快照（JSON 文本，本环节=策略快照） */
    private String capabilitySnapshot;

    /** 子步骤列表 */
    private List<StepLogVO> steps;

    /** 集合摘要（最新集合；无集合时为空） */
    private EmbedSummaryVO summary;

    /** 向量记录列表（集合内顺序；无集合时为空列表） */
    private List<EmbedRecordItemVO> records;
}
