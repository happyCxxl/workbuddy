package com.workbuddy.common.enums.index;

/**
 * 索引版本状态机（策略集合模型）：
 * CREATED → BUILDING → READY →（发布）ONLINE；ONLINE →（新组合上线/回退切走）RETIRED；
 * RETIRED →（回退复活）ONLINE；任意非终态可 FAILED；回收 = 物理删除 + drop 集合（无状态）。
 */
public enum IndexVersionStatus {

    /** 已创建（组合注册，集合建设中） */
    CREATED,

    /** 构建中（集合追加累积/对账中） */
    BUILDING,

    /** 就绪（全量对账通过 + 预热完成，等待发布） */
    READY,

    /** 在线（已发布；持续追加，状态不流转） */
    ONLINE,

    /** 构建失败 */
    FAILED,

    /** 已退役（新组合上线或回退切走时置；集合保留可回退） */
    RETIRED
}
