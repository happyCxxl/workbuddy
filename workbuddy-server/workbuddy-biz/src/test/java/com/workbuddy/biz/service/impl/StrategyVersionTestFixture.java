package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.support.retrieval.RetrievalRuleResolver;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.dto.request.strategy.StrategyVersionCreateDTO;
import com.workbuddy.common.dto.request.strategy.StrategyVersionUpdateDTO;

import static org.mockito.Mockito.mock;

/**
 * 策略版本测试夹具：装配被测服务（边界替身）与各类型策略的创建/更新 DTO、版本行数据。
 *
 * <p>增删改查与配置校验两组用例共用；各测试类不再重复声明依赖。
 */
final class StrategyVersionTestFixture {

    final WbPipelineStrategyVersionDbService strategyVersionDbService =
            mock(WbPipelineStrategyVersionDbService.class);
    final WbStrategyBindingDbService strategyBindingDbService = mock(WbStrategyBindingDbService.class);
    final RetrievalRuleResolver retrievalRuleResolver = mock(RetrievalRuleResolver.class);

    private final StrategyVersionServiceImpl service;

    StrategyVersionTestFixture() {
        service = new StrategyVersionServiceImpl(strategyVersionDbService, strategyBindingDbService,
                retrievalRuleResolver);
    }

    /** 被测服务 */
    StrategyVersionServiceImpl service() {
        return service;
    }

    /** CHUNK 策略版本行夹具（指定状态） */
    WbPipelineStrategyVersion row(Long id, String version, String status) {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(id);
        row.setType("CHUNK");
        row.setName("chunk-hybrid");
        row.setVersion(version);
        row.setConfigSnapshot("{\"routes\":{}}");
        row.setCreateTime(java.time.LocalDateTime.of(2026, 10, 9, 10, 0));
        row.setStatus(status);
        return row;
    }

    /** 新建请求夹具：CHUNK 正文段落聚合 */
    StrategyVersionCreateDTO createDto() {
        StrategyVersionCreateDTO dto = new StrategyVersionCreateDTO();
        dto.setType("CHUNK");
        dto.setName("chunk-hybrid");
        dto.setVersion("v2");
        dto.setConfigSnapshot("{\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\"}}}");
        return dto;
    }

    /** 编辑请求夹具：复制为新版本 */
    StrategyVersionUpdateDTO updateDto() {
        StrategyVersionUpdateDTO dto = new StrategyVersionUpdateDTO();
        dto.setName("chunk-strict");
        dto.setVersion("v2");
        dto.setConfigSnapshot("{\"routes\":{\"body\":{\"algorithm\":\"title-boundary\"}}}");
        return dto;
    }

    /** CHUNK 策略创建 DTO：routes/pipeline 配置结构 */
    StrategyVersionCreateDTO chunkDto(String configSnapshot) {
        StrategyVersionCreateDTO dto = new StrategyVersionCreateDTO();
        dto.setType("CHUNK");
        dto.setName("chunk-hybrid");
        dto.setVersion("v1");
        dto.setConfigSnapshot(configSnapshot);
        return dto;
    }

    /** PREPROCESS 策略创建 DTO */
    StrategyVersionCreateDTO preprocDto(String configSnapshot) {
        StrategyVersionCreateDTO dto = new StrategyVersionCreateDTO();
        dto.setType("PREPROCESS");
        dto.setName("preproc-strict");
        dto.setVersion("v2");
        dto.setConfigSnapshot(configSnapshot);
        return dto;
    }

    /** EMBED 策略创建 DTO */
    StrategyVersionCreateDTO embedDto(String configSnapshot) {
        StrategyVersionCreateDTO dto = new StrategyVersionCreateDTO();
        dto.setType("EMBED");
        dto.setName("embed-default");
        dto.setVersion("v1");
        dto.setConfigSnapshot(configSnapshot);
        return dto;
    }

    /** RETRIEVAL 策略创建 DTO */
    StrategyVersionCreateDTO retrievalDto(String configSnapshot) {
        StrategyVersionCreateDTO dto = new StrategyVersionCreateDTO();
        dto.setType("RETRIEVAL");
        dto.setName("hybrid-rrf-k60-top10");
        dto.setVersion("v1");
        dto.setConfigSnapshot(configSnapshot);
        return dto;
    }
}
