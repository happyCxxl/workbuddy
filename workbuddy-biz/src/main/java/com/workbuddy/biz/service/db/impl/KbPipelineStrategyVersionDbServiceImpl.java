package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workbuddy.biz.mapper.KbPipelineStrategyVersionMapper;
import com.workbuddy.biz.service.db.KbPipelineStrategyVersionDbService;
import com.workbuddy.common.domain.entity.KbPipelineStrategyVersion;
import com.workbuddy.common.enums.strategy.StrategyType;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 环节策略版本数据访问服务实现。
 *
 * @author cxxl
 */
@Service
public class KbPipelineStrategyVersionDbServiceImpl
        extends InfraDbServiceImpl<KbPipelineStrategyVersionMapper, KbPipelineStrategyVersion>
        implements KbPipelineStrategyVersionDbService {

    @Override
    public List<KbPipelineStrategyVersion> listByType(String type) {
        return list(byType(type));
    }

    @Override
    public List<KbPipelineStrategyVersion> listEnabledByType(String type) {
        return list(enabledByType(type));
    }

    @Override
    public KbPipelineStrategyVersion getByTypeAndNameAndVersion(String type, String name, String version) {
        LambdaQueryWrapper<KbPipelineStrategyVersion> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbPipelineStrategyVersion::getType, type)
                .eq(KbPipelineStrategyVersion::getName, name)
                .eq(KbPipelineStrategyVersion::getVersion, version)
                .orderByDesc(KbPipelineStrategyVersion::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public long countEnabledByType(StrategyType type) {
        LambdaQueryWrapper<KbPipelineStrategyVersion> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbPipelineStrategyVersion::getType, type.key())
                .eq(KbPipelineStrategyVersion::getStatus, RowStatus.ACTIVE.name());
        return count(queryWrapper);
    }

    /** 按策略类型查询条件（新→旧） */
    private LambdaQueryWrapper<KbPipelineStrategyVersion> byType(String type) {
        LambdaQueryWrapper<KbPipelineStrategyVersion> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbPipelineStrategyVersion::getType, type)
                .orderByDesc(KbPipelineStrategyVersion::getId);
        return queryWrapper;
    }

    /** 按策略类型 + 仅启用中 的查询条件（新→旧） */
    private LambdaQueryWrapper<KbPipelineStrategyVersion> enabledByType(String type) {
        return byType(type).eq(KbPipelineStrategyVersion::getStatus, RowStatus.ACTIVE.name());
    }
}
