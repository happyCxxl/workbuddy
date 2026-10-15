package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workbuddy.biz.mapper.WbPipelineStrategyVersionMapper;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
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
public class WbPipelineStrategyVersionDbServiceImpl
        extends InfraDbServiceImpl<WbPipelineStrategyVersionMapper, WbPipelineStrategyVersion>
        implements WbPipelineStrategyVersionDbService {

    @Override
    public List<WbPipelineStrategyVersion> listByType(String type) {
        return list(byType(type));
    }

    @Override
    public List<WbPipelineStrategyVersion> listEnabledByType(String type) {
        return list(enabledByType(type));
    }

    @Override
    public WbPipelineStrategyVersion getByTypeAndNameAndVersion(String type, String name, String version) {
        LambdaQueryWrapper<WbPipelineStrategyVersion> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineStrategyVersion::getType, type)
                .eq(WbPipelineStrategyVersion::getName, name)
                .eq(WbPipelineStrategyVersion::getVersion, version)
                .orderByDesc(WbPipelineStrategyVersion::getId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public long countEnabledByType(StrategyType type) {
        LambdaQueryWrapper<WbPipelineStrategyVersion> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineStrategyVersion::getType, type.key())
                .eq(WbPipelineStrategyVersion::getStatus, RowStatus.ACTIVE.name());
        return count(queryWrapper);
    }

    /** 按策略类型查询条件（新→旧） */
    private LambdaQueryWrapper<WbPipelineStrategyVersion> byType(String type) {
        LambdaQueryWrapper<WbPipelineStrategyVersion> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbPipelineStrategyVersion::getType, type)
                .orderByDesc(WbPipelineStrategyVersion::getId);
        return queryWrapper;
    }

    /** 按策略类型 + 仅启用中 的查询条件（新→旧） */
    private LambdaQueryWrapper<WbPipelineStrategyVersion> enabledByType(String type) {
        return byType(type).eq(WbPipelineStrategyVersion::getStatus, RowStatus.ACTIVE.name());
    }
}
