package com.workbuddy.biz.service.db.impl;

import com.workbuddy.biz.mapper.KbStorageSourceMapper;
import com.workbuddy.biz.service.db.KbStorageSourceDbService;
import com.workbuddy.common.domain.entity.KbStorageSource;
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
public class KbStorageSourceDbServiceImpl extends InfraDbServiceImpl<KbStorageSourceMapper, KbStorageSource>
        implements KbStorageSourceDbService {

    @Override
    public List<KbStorageSource> listAll() {
        return this.lambdaQuery().orderByAsc(KbStorageSource::getId).list();
    }

    @Override
    public void clearCurrent() {
        List<KbStorageSource> rows = this.lambdaQuery().eq(KbStorageSource::getIsCurrent, CURRENT_YES).list();
        rows.forEach(row -> {
            row.setIsCurrent(CURRENT_NO);
            updateById(row);
        });
    }

    @Override
    public void markCurrent(Long id) {
        KbStorageSource row = new KbStorageSource();
        row.setId(id);
        row.setIsCurrent(CURRENT_YES);
        updateById(row);
    }

    @Override
    public void recordProbe(Long id, boolean probeOk, LocalDateTime probeAt) {
        this.lambdaUpdate()
                .set(KbStorageSource::getLastProbeOk, probeOk)
                .set(KbStorageSource::getLastProbeAt, probeAt)
                .eq(KbStorageSource::getId, id)
                .update();
    }
}
