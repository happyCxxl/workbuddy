package com.workbuddy.biz.service;

import com.workbuddy.common.dto.response.embed.EmbedDetailVO;
import com.workbuddy.common.dto.response.embed.EmbedTriggerVO;

/**
 * 向量化控制面服务（手动逐环节，与切片/预处理同构）。
 */
public interface EmbedControlService {

    /**
     * 触发向量化/重跑：策略解析两档（显式指定 / 知识库绑定，其余 40438）→ 上游 CHUNK 产物校验（必填）→
     * 窗口前置校验（不兼容 40435）→ 执行键查重（已有任务一律拒绝 40431）→ 建任务入队。
     */
    EmbedTriggerVO embed(Long fileResultId, Long strategyVersionId, Long upstreamProductId);

    /**
     * 向量化详情：任务状态 + 子步骤 + 集合摘要/记录 + 产物引用；taskId 可选（缺省最新任务）。
     */
    EmbedDetailVO embedDetail(Long fileResultId, Long taskId);
}
