package com.workbuddy.biz.service;

import com.workbuddy.common.dto.response.chunk.ChunkDetailVO;
import com.workbuddy.common.dto.response.chunk.ChunkTriggerVO;

/**
 * 切片控制面：触发切片/重跑（手动逐环节）+ 切片详情。
 */
public interface ChunkControlService {

    /**
     * 触发切片（strategyVersionId 可选：显式指定策略版本行 ID（校验存在/类型/启用，任一不满足 40433）；
     * 不传用知识库绑定；两档都不命中一律 40438）。
     *
     * @param fileResultId       文件结果 ID
     * @param strategyVersionId  策略版本行 ID（可空）
     * @param upstreamProductId 上游产物 ID（必填，指定 PREPROCESS 产物，未指定或传 0 报 40436）
     * @return 任务 ID + 生效策略版本
     */
    ChunkTriggerVO chunk(Long fileResultId, Long strategyVersionId, Long upstreamProductId);

    /**
     * 切片详情：任务状态 + 子步骤 + 切片统计/切片列表/产物引用（来自最新切片集合与 wb_knowledge_chunk）；
     * taskId 可选（缺省取最新任务，传了则查该次运行）。
     */
    ChunkDetailVO chunkDetail(Long fileResultId, Long taskId);
}
