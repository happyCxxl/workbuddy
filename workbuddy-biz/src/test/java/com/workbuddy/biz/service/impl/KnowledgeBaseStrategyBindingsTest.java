package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.support.access.KnowledgeBaseAccessSupport;
import com.workbuddy.biz.service.support.access.KnowledgeBaseBindingSupport;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbStrategyBinding;
import com.workbuddy.common.dto.response.knowledge.StrategyBindingVO;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 策略绑定批量查询单测。
 *
 * <p>守护 {@code strategyBindings} 的三条口径：类型白名单校验、
 * 版本被删/停用时跳过不返回半截数据、全量取绑定行（knowledgeBaseIds 传 null）。
 */
class KnowledgeBaseStrategyBindingsTest extends KnowledgeBaseTestFixture {

    private WbKnowledgeBaseDbService knowledgeBaseDbService;

    private WbStrategyBindingDbService strategyBindingDbService;

    private WbPipelineStrategyVersionDbService strategyVersionDbService;

    private KnowledgeBaseBindingServiceImpl service;

    /**
     * 每个用例重建批量查询服务（批量查询本身不校验归属，无需登录态）。
     */
    @BeforeEach
    void setUp() {
        knowledgeBaseDbService = mock(WbKnowledgeBaseDbService.class);
        strategyBindingDbService = mock(WbStrategyBindingDbService.class);
        strategyVersionDbService = mock(WbPipelineStrategyVersionDbService.class);
        service = new KnowledgeBaseBindingServiceImpl(strategyBindingDbService,
                strategyVersionDbService, new KnowledgeBaseAccessSupport(knowledgeBaseDbService),
                new KnowledgeBaseBindingSupport(strategyBindingDbService, strategyVersionDbService));
    }

    /**
     * 未知策略类型被拒绝。
     *
     * <p>守护 {@code strategyBindings} 的类型白名单（错误码 40001）。
     */
    @Test
    void unknownTypeShouldReject() {
        WorkbuddyException ex = assertThrows(WorkbuddyException.class,
                () -> service.strategyBindings("NO_SUCH"));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    /**
     * 无绑定时返回空列表。
     *
     * <p>守护 {@code strategyBindings} 的空集短路。
     */
    @Test
    void emptyBindingsShouldReturnEmptyList() {
        when(strategyBindingDbService.listActiveByTypeAndKbIds("CHUNK", null)).thenReturn(List.of());

        assertTrue(service.strategyBindings("CHUNK").isEmpty());
    }

    /**
     * 版本被删/停用时不返回半截数据，只返回版本完整的行。
     *
     * <p>守护 {@code strategyBindings} 的版本过滤：把无版本的行也返回即失败。
     */
    @Test
    void shouldSkipBindingsWithoutVersion() {
        WbStrategyBinding orphan = bindingOf(1L, "CHUNK", 999L);
        WbStrategyBinding valid = bindingOf(2L, "CHUNK", 500L);
        when(strategyBindingDbService.listActiveByTypeAndKbIds("CHUNK", null))
                .thenReturn(List.of(orphan, valid));
        WbPipelineStrategyVersion version = versionOf(500L, "CHUNK", "chunk-hybrid", "v1");
        when(strategyVersionDbService.listByIds(any())).thenReturn(List.of(version));

        List<StrategyBindingVO> result = service.strategyBindings("CHUNK");

        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).getKnowledgeBaseId());
        assertEquals("CHUNK", result.get(0).getStrategyType());
        assertEquals(500L, result.get(0).getStrategyVersionId());
        assertEquals("chunk-hybrid", result.get(0).getStrategyName());
        assertEquals("v1", result.get(0).getStrategyVersion());
    }
}
