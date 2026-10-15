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
 *
 * @author cxxl
 */
@Service
public class WbStorageSourceDbServiceImpl extends InfraDbServiceImpl<WbStorageSourceMapper, WbStorageSource>
        implements WbStorageSourceDbService {

    @Override
    public List<WbStorageSource> listAll() {
        return this.lambdaQuery().orderByAsc(WbStorageSource::getId).list();
    }

    @Override
    public void clearCurrent() {
        List<WbStorageSource> rows = this.lambdaQuery().eq(WbStorageSource::getIsCurrent, CURRENT_YES).list();
        rows.forEach(row -> {
            row.setIsCurrent(CURRENT_NO);
            updateById(row);
        });
    }

    @Override
    public void markCurrent(Long id) {
        WbStorageSource row = new WbStorageSource();
        row.setId(id);
        row.setIsCurrent(CURRENT_YES);
        updateById(row);
    }

    @Override
    public void recordProbe(Long id, boolean probeOk, LocalDateTime probeAt) {
        this.lambdaUpdate()
                .set(WbStorageSource::getLastProbeOk, probeOk)
                .set(WbStorageSource::getLastProbeAt, probeAt)
                .eq(WbStorageSource::getId, id)
                .update();
    }
}
