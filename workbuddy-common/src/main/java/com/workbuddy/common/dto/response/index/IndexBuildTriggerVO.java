package com.workbuddy.common.dto.response.index;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 触发索引构建响应：版本行 + 构建任务 ID（页面据此轮询构建状态）。
 *
 * @author cxxl
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndexBuildTriggerVO {

    /** 索引版本行 ID（wb_knowledge_index_version.id） */
    private Long versionId;

    /** 版本号（v1、v2…） */
    private String versionNo;

    /** 构建任务 ID（wb_knowledge_pipeline_task.id，stage=BUILD_INDEX） */
    private Long pipelineTaskId;
}
