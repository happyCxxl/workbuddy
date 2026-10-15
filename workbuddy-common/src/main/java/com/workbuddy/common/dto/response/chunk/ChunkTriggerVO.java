package com.workbuddy.common.dto.response.chunk;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 触发切片响应：任务 ID + 生效策略版本（触发时固定）。
 *
 * @author cxxl
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkTriggerVO {

    /** 文件结果 ID（wb_file_result.id） */
    private Long fileResultId;

    /** 处理链任务 ID（wb_knowledge_pipeline_task.id） */
    private Long pipelineTaskId;

    /** 生效策略版本（如 chunk-hybrid-v1） */
    private String strategyVersion;
}
