package com.workbuddy.biz.service.db;

import com.workbuddy.common.domain.entity.WbStorageSource;
import com.workbuddy.infra.persistence.InfraDbService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 存储数据源数据访问服务（表：wb_storage_source）。
 *
 * @author cxxl
 */
public interface WbStorageSourceDbService extends InfraDbService<WbStorageSource> {

    /** 当前启用标记：1 是 */
    int CURRENT_YES = 1;

    /** 当前启用标记：0 否 */
    int CURRENT_NO = 0;

    /** 全部未删除的数据源行（主键升序） */
    List<WbStorageSource> listAll();

    /** 清除当前启用标记（全表至多一行为 1，置新的之前先清） */
    void clearCurrent();

    /**
     * 标记当前启用的数据源行。
     *
     * @param id 数据源 ID
     */
    void markCurrent(Long id);

    /**
     * 回写最近一次连接探测结果（只动这两列，不改连接参数与启用状态）。
     *
     * @param id      数据源 ID
     * @param probeOk 探测结论：通过 true / 失败 false
     * @param probeAt 探测时间
     */
    void recordProbe(Long id, boolean probeOk, LocalDateTime probeAt);
}
