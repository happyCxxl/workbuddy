package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.StrategyVersionService;
import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.dto.request.retrieval.RetrievalRulePublishDTO;
import com.workbuddy.common.dto.response.retrieval.RetrievalRulePublishVO;
import com.workbuddy.common.dto.response.strategy.StrategyVersionVO;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索规则服务单测：列表委托 RETRIEVAL 白名单 / 选优发布切版本行默认规则 + 审计 /
 * 规则不存在或类型不符 40450 / 版本行不属本库拒绝。
 */
@ExtendWith(MockitoExtension.class)
class RetrievalRuleServiceImplTest {

    @Mock
    private StrategyVersionService strategyVersionService;
    @Mock
    private WbPipelineStrategyVersionDbService strategyVersionDbService;
    @Mock
    private WbIndexVersionDbService indexVersionDbService;
    @Mock
    private WbIndexSetDbService indexSetDbService;
    @Mock
    private WbAuditLogDbService kbAuditLogDbService;

    private RetrievalRuleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RetrievalRuleServiceImpl(strategyVersionService, strategyVersionDbService,
                indexVersionDbService, indexSetDbService, kbAuditLogDbService);
    }

    private WbIndexVersion versionRow() {
        WbIndexVersion version = new WbIndexVersion();
        version.setId(20L);
        version.setIndexSetId(5L);
        version.setVersionNo("v2");
        return version;
    }

    private WbIndexSet setRow(Long kbId) {
        WbIndexSet set = new WbIndexSet();
        set.setId(5L);
        set.setKnowledgeBaseId(kbId);
        return set;
    }

    private WbPipelineStrategyVersion ruleRow(String type) {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(500L);
        row.setType(type);
        row.setName("hybrid-rrf-k60-top10");
        row.setVersion("v1");
        row.setConfigSnapshot("{\"channel\":\"HYBRID\"}");
        return row;
    }

    private RetrievalRulePublishDTO dto() {
        RetrievalRulePublishDTO dto = new RetrievalRulePublishDTO();
        dto.setVersionId(20L);
        dto.setRuleId(500L);
        return dto;
    }

    /**
     * 校验点：{@code listRulesShouldDelegateToRetrievalType}。
     */
    @Test
    void listRulesShouldDelegateToRetrievalType() {
        when(strategyVersionService.list("RETRIEVAL", false)).thenReturn(List.of());

        List<StrategyVersionVO> rules = service.listRules(false);

        assertEquals(List.of(), rules);
        verify(strategyVersionService).list("RETRIEVAL", false);

        // 有规则时原样下发：恒空列表会让规则下拉永远为空
        StrategyVersionVO rule = new StrategyVersionVO();
        rule.setId(9L);
        when(strategyVersionService.list("RETRIEVAL", true)).thenReturn(List.of(rule));
        assertEquals(1, service.listRules(true).size());
    }

    /**
     * 校验点：{@code publishShouldSwitchVersionDefaultRuleAndAudit}。
     */
    @Test
    void publishShouldSwitchVersionDefaultRuleAndAudit() {
        WbIndexVersion version = versionRow();
        when(indexVersionDbService.getById(20L)).thenReturn(version);
        when(indexSetDbService.getById(5L)).thenReturn(setRow(1L));
        when(strategyVersionDbService.getById(500L)).thenReturn(ruleRow("RETRIEVAL"));

        RetrievalRulePublishVO vo = service.publishDefaultRule(1L, dto());

        assertEquals(500L, version.getDefaultRuleId());
        verify(indexVersionDbService).updateById(version);
        verify(kbAuditLogDbService).saveAudit(AuditActionType.RETRIEVAL_RULE_PUBLISH,
                "INDEX_VERSION", 20L, null, "500");
        assertEquals("hybrid-rrf-k60-top10-v1", vo.getRuleNameVersion());
        assertEquals("v2", vo.getVersionNo());
        // 发布结果必须带规则与版本主键（漏搬会让前端无法回显）
        assertEquals(500L, vo.getRuleId());
        assertEquals(20L, vo.getVersionId());
    }

    /**
     * 校验点：{@code publishShouldRejectRuleNotFoundOrWrongType}。
     */
    @Test
    void publishShouldRejectRuleNotFoundOrWrongType() {
        when(indexVersionDbService.getById(20L)).thenReturn(versionRow());
        when(indexSetDbService.getById(5L)).thenReturn(setRow(1L));
        when(strategyVersionDbService.getById(500L)).thenReturn(null);

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.publishDefaultRule(1L, dto()));
        assertEquals(ErrorCode.RETRIEVAL_RULE_NOT_FOUND, e.getErrorCode());

        when(strategyVersionDbService.getById(500L)).thenReturn(ruleRow("CHUNK"));
        WorkbuddyException e2 = assertThrows(WorkbuddyException.class,
                () -> service.publishDefaultRule(1L, dto()));
        assertEquals(ErrorCode.RETRIEVAL_RULE_NOT_FOUND, e2.getErrorCode());
        verify(indexVersionDbService, never()).updateById(org.mockito.ArgumentMatchers.any());
    }

    /**
     * 校验点：{@code publishShouldRejectVersionNotBelongingToKb}。
     */
    @Test
    void publishShouldRejectVersionNotBelongingToKb() {
        when(indexVersionDbService.getById(20L)).thenReturn(versionRow());
        when(indexSetDbService.getById(5L)).thenReturn(setRow(2L));

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.publishDefaultRule(1L, dto()));

        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 发布默认规则时版本不存在：按 40441 拒绝。
     *
     * <p>守护 {@code publishDefaultRule} 的版本校验：漏校验会在空版本上写默认规则。
     */
    @Test
    void publishShouldRejectMissingVersion() {
        when(indexVersionDbService.getById(20L)).thenReturn(null);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.publishDefaultRule(1L, dto()));

        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND, e.getErrorCode());
    }
}
