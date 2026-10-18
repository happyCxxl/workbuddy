package com.workbuddy.biz.service.db;

import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.enums.strategy.StrategyType;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.List;

/**
 * 环节策略版本数据访问服务。
 */
public interface WbPipelineStrategyVersionDbService extends InfraDbService<WbPipelineStrategyVersion> {

    /**
     * 取类型下全部版本（含停用，新→旧）。
     */
    List<WbPipelineStrategyVersion> listByType(String type);

    /**
     * 取类型下启用中的版本（新→旧）。
     */
    List<WbPipelineStrategyVersion> listEnabledByType(String type);

    /**
     * 按类型+名称+版本取任意状态版本行（无 → null；索引组合回填数据源）
     */
    WbPipelineStrategyVersion getByTypeAndNameAndVersion(String type, String name, String version);

    /**
     * 取类型下启用中的版本条数（status = ACTIVE），供概览类计数使用。
     */
    long countEnabledByType(StrategyType type);
}
