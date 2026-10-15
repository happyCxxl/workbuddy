package com.workbuddy.common.dto.response.task;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 环节触发响应：parse/structure 等纯触发接口的通用响应（返回新登记的任务 ID）。
 * preprocess/chunk 因需回显生效策略版本，使用各自带策略版本的响应。
 *
 * @author cxxl
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StageTriggerVO {

    /** 处理链任务 ID（wb_knowledge_pipeline_task.id） */
    private Long pipelineTaskId;
}
