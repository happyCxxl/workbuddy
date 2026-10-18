package com.workbuddy.common.enums.task;

/**
 * 数据行状态（字典）：跨表通用的行级生命周期状态。
 * 适用表：wb_knowledge_pipeline_strategy_version / wb_knowledge_chunk_set / wb_knowledge_embedding_set。
 * 落库形式：name()。
 */
public enum RowStatus {

    /** 启用/生效中（参与查询与触发） */
    ACTIVE,

    /** 停用（策略版本启用/停用） */
    INACTIVE
}
