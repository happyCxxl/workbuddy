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
 */
@Service
public class WbPipelineStrategyVersionDbServiceImpl
        extends InfraDbServiceImpl<WbPipelineStrategyVersionMapper, WbPipelineStrategyVersion>
        implements WbPipelineStrategyVersionDbService {

    /**
     * 取某策略类型下的全部版本。
     *
     * @param type 策略类型码值
     * @return 版本列表
     */
    @Override
    public List<WbPipelineStrategyVersion> listByType(String type) {
        return list(byType(type));
    }

    /**
     * 取某策略类型下启用中的版本。
     *
     * @param type 策略类型码值
     * @return 启用中的版本列表
     */
    @Override
    public List<WbPipelineStrategyVersion> listEnabledByType(String type) {
        return list(enabledByType(type));
    }

    /**
     * 按类型、名称与版本号取策略版本。
     *
     * @param type    策略类型码值
     * @param name    策略名称
     * @param version 版本号
     * @return 版本行；没有则为 null
     */
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

    /**
     * 统计某策略类型下启用中的版本数。
     *
     * @param type 策略类型
     * @return 版本数
     */
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
