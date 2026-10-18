package com.workbuddy.worker.retrieval;

/**
 * 检索能力（能力分层解锁）：
 * 预留未开放的能力清单（默认全关）——规则注册校验与执行引擎按此拒执行（40451），
 * 解锁 = 配置项 `retrieval.capabilities.enabled` 加枚举名，结构零迁移。
 * 恒开的能力（三通道/RRF/父片展开）不进本清单。
 */
public enum RetrievalCapability {

    /** 融合-加权（预留） */
    WEIGHTED,

    /** 查询改写（预留，依赖 LLM 网关） */
    REWRITE,

    /** 查询扩展（预留，依赖 LLM 网关） */
    EXPAND,

    /** 重排（预留，依赖 llm-pool rerank 模型） */
    RERANK,

    /** 相邻片扩展（预留） */
    NEIGHBOR_EXPAND,

    /** 向量相似度阈值截断（预留） */
    SCORE_THRESHOLD
}
