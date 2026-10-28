package com.workbuddy.biz.service.db.impl;

import com.workbuddy.biz.mapper.WbStorageSourceMapper;
import com.workbuddy.biz.service.db.WbStorageSourceDbService;
import com.workbuddy.common.domain.entity.WbStorageSource;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 存储数据源数据访问服务实现。
 */
@Service
public class WbStorageSourceDbServiceImpl extends InfraDbServiceImpl<WbStorageSourceMapper, WbStorageSource>
        implements WbStorageSourceDbService {

    /**
     * 列出全部存储数据源。
     *
     * @return 数据源列表
     */
    @Override
    public List<WbStorageSource> listAll() {
        return this.lambdaQuery().orderByAsc(WbStorageSource::getId).list();
    }

    /** 清空当前启用标记。 */
    @Override
    public void clearCurrent() {
        List<WbStorageSource> rows = this.lambdaQuery().eq(WbStorageSource::getIsCurrent, CURRENT_YES).list();
        rows.forEach(row -> {
            row.setIsCurrent(CURRENT_NO);
            updateById(row);
        });
    }

    /**
     * 把指定数据源标记为当前启用（先清空其余）。
     *
     * @param id 数据源 ID
     */
    @Override
    public void markCurrent(Long id) {
        WbStorageSource row = new WbStorageSource();
        row.setId(id);
        row.setIsCurrent(CURRENT_YES);
        updateById(row);
    }

    /**
     * 记录一次连接探活结果。
     *
     * @param id      数据源 ID
     * @param probeOk 探活是否通过
     * @param probeAt 探活时间
     */
    @Override
    public void recordProbe(Long id, boolean probeOk, LocalDateTime probeAt) {
        this.lambdaUpdate()
                .set(WbStorageSource::getLastProbeOk, probeOk)
                .set(WbStorageSource::getLastProbeAt, probeAt)
                .eq(WbStorageSource::getId, id)
                .update();
    }
}
