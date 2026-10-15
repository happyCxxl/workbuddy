package com.workbuddy.biz.service.db;

import com.workbuddy.common.domain.entity.KbPipelineStrategyVersion;
import com.workbuddy.common.enums.strategy.StrategyType;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.List;

/**
 * 环节策略版本数据访问服务。
 *
 * @author cxxl
 */
public interface KbPipelineStrategyVersionDbService extends InfraDbService<KbPipelineStrategyVersion> {

    /**
     * 取类型下全部版本（含停用，新→旧）。
     */
    List<KbPipelineStrategyVersion> listByType(String type);

    /**
     * 取类型下启用中的版本（新→旧）。
     */
    List<KbPipelineStrategyVersion> listEnabledByType(String type);

    /**
     * 按类型+名称+版本取任意状态版本行（无 → null；索引组合回填数据源）
     */
    KbPipelineStrategyVersion getByTypeAndNameAndVersion(String type, String name, String version);

    /**
     * 取类型下启用中的版本条数（status = ACTIVE），供概览类计数使用。
     */
    long countEnabledByType(StrategyType type);
}
