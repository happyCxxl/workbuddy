package com.workbuddy.common.dto.response.input;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 提交文件响应：submitLog 整条（含 requestId 回显）+ 处理链任务 ID。
 *
 * <p>手动逐环节口径：首次提交时 pipelineTaskId 为空
 * （解析由页面触发 POST /file-results/{id}/parse）；
 * 幂等回放时携带该文件结果已有的解析任务 ID（无任务时同样为空）。
 *
 * @author cxxl
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileSubmitVO {

    /** 提交日志（整条回显） */
    private SubmitLogVO submitLog;

    /** 处理链任务 ID（实际为 PARSE 环节任务 wb_knowledge_pipeline_task.id；首次提交/校验失败/无任务时为空） */
    private Long pipelineTaskId;
}
