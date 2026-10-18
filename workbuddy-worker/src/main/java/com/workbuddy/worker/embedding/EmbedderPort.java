package com.workbuddy.worker.embedding;

import com.workbuddy.common.domain.embed.EmbedOutcome;

/**
 * 向量化编排入口：切片文本按绑定策略原样编码为向量本体（EmbeddingSet）。
 * 纯算法，不碰 DB/产物存储（落库回写由 biz EmbedTaskRunner 编排）；确定性执行（状态全在 EmbedContext）。
 */
public interface EmbedderPort {

    /**
     * 执行向量化：前置校验 → 筛选 → 编码 → 复用判定（账本回溯）→ 分批模型调用 → 四关 → 组装。
     *
     * @param context 向量化上下文（切片产物/策略/参数/复用候选账本）
     * @return 向量化输出（产物集合 + 子步骤 + 状态建议）
     */
    EmbedOutcome embed(EmbedContext context);
}
