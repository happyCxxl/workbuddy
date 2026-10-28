package com.workbuddy.biz.service;

/**
 * 索引版本回退与回收用例：回退在单事务内切换指针并驱动构建补齐；回收在事务外 drop 集合。
 */
public interface IndexVersionService {

    /**
     * 回退到指定索引版本：指针切回（同事务，旧在线退役、目标复活 ONLINE）+ 审计 + 拓扑补齐。
     *
     * @param versionId 版本 ID
     */
    void rollback(Long versionId);

    /**
     * 回收索引候选：禁删在线/构建中/已退役版本 → 事务外 drop 集合 → 短事务审计并删行。
     *
     * @param versionId 版本 ID
     */
    void recycle(Long versionId);
}
