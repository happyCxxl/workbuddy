package com.workbuddy.common.enums.task;

/**
 * 数据行状态（字典）：跨表通用的行级生命周期状态。
 * 适用表：kb_pipeline_strategy_version / kb_chunk_set / kb_embedding_set。
 * 落库形式：name()。
 *
 * @author cxxl
 */
public enum RowStatus {

    /** 启用/生效中（参与查询与触发） */
    ACTIVE,

    /** 停用（策略版本启用/停用） */
    INACTIVE
}
