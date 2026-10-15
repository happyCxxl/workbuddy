package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.StrategyVersionService;
import com.workbuddy.biz.service.db.KbAuditLogDbService;
import com.workbuddy.biz.service.db.KbIndexSetDbService;
import com.workbuddy.biz.service.db.KbIndexVersionDbService;
import com.workbuddy.biz.service.db.KbPipelineStrategyVersionDbService;
import com.workbuddy.common.domain.entity.KbIndexSet;
import com.workbuddy.common.domain.entity.KbIndexVersion;
import com.workbuddy.common.domain.entity.KbPipelineStrategyVersion;
import com.workbuddy.common.dto.request.retrieval.RetrievalRulePublishDto;
import com.workbuddy.common.dto.response.retrieval.RetrievalRulePublishVO;
import com.workbuddy.common.dto.response.strategy.StrategyVersionVO;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
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
 *
 * @author cxxl
 */
@ExtendWith(MockitoExtension.class)
class RetrievalRuleServiceImplTest {

    @Mock
    private StrategyVersionService strategyVersionService;
    @Mock
    private KbPipelineStrategyVersionDbService strategyVersionDbService;
    @Mock
    private KbIndexVersionDbService indexVersionDbService;
    @Mock
    private KbIndexSetDbService indexSetDbService;
    @Mock
    private KbAuditLogDbService kbAuditLogDbService;

    private RetrievalRuleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RetrievalRuleServiceImpl(strategyVersionService, strategyVersionDbService,
                indexVersionDbService, indexSetDbService, kbAuditLogDbService);
    }

    private KbIndexVersion versionRow() {
        KbIndexVersion version = new KbIndexVersion();
        version.setId(20L);
        version.setIndexSetId(5L);
        version.setVersionNo("v2");
        return version;
    }

    private KbIndexSet setRow(Long kbId) {
        KbIndexSet set = new KbIndexSet();
        set.setId(5L);
        set.setKnowledgeBaseId(kbId);
        return set;
    }

    private KbPipelineStrategyVersion ruleRow(String type) {
        KbPipelineStrategyVersion row = new KbPipelineStrategyVersion();
        row.setId(500L);
        row.setType(type);
        row.setName("hybrid-rrf-k60-top10");
        row.setVersion("v1");
        row.setConfigSnapshot("{\"channel\":\"HYBRID\"}");
        return row;
    }

    private RetrievalRulePublishDto dto() {
        RetrievalRulePublishDto dto = new RetrievalRulePublishDto();
        dto.setVersionId(20L);
        dto.setRuleId(500L);
        return dto;
    }

    @Test
    void listRulesShouldDelegateToRetrievalType() {
        when(strategyVersionService.list("RETRIEVAL", false)).thenReturn(List.of());

        List<StrategyVersionVO> rules = service.listRules(false);

        assertEquals(List.of(), rules);
        verify(strategyVersionService).list("RETRIEVAL", false);
    }

    @Test
    void publishShouldSwitchVersionDefaultRuleAndAudit() {
        KbIndexVersion version = versionRow();
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
    }

    @Test
    void publishShouldRejectRuleNotFoundOrWrongType() {
        when(indexVersionDbService.getById(20L)).thenReturn(versionRow());
        when(indexSetDbService.getById(5L)).thenReturn(setRow(1L));
        when(strategyVersionDbService.getById(500L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.publishDefaultRule(1L, dto()));
        assertEquals(ErrorCode.RETRIEVAL_RULE_NOT_FOUND, e.getErrorCode());

        when(strategyVersionDbService.getById(500L)).thenReturn(ruleRow("CHUNK"));
        KnowledgeException e2 = assertThrows(KnowledgeException.class,
                () -> service.publishDefaultRule(1L, dto()));
        assertEquals(ErrorCode.RETRIEVAL_RULE_NOT_FOUND, e2.getErrorCode());
        verify(indexVersionDbService, never()).updateById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publishShouldRejectVersionNotBelongingToKb() {
        when(indexVersionDbService.getById(20L)).thenReturn(versionRow());
        when(indexSetDbService.getById(5L)).thenReturn(setRow(2L));

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> service.publishDefaultRule(1L, dto()));

        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND, e.getErrorCode());
    }
}
