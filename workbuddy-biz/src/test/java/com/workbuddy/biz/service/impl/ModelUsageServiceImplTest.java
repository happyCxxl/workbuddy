package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.model.calllog.ModelCallLogQuery;
import com.workbuddy.common.domain.model.calllog.ModelCallLogSummary;
import com.workbuddy.common.domain.model.calllog.ModelCallLogView;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.dto.request.model.ModelUsageQueryDto;
import com.workbuddy.common.enums.model.CallStatus;
import com.workbuddy.common.enums.model.RouteReason;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.model.calllog.ModelCallLogPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用量服务单测（端口打桩）：收参解析（时间两种写法、字典取值、模型名去空白）、
 * 非法取值与缺失时间范围的拒绝口径、概览与明细两个入口的转发。
 *
 * @author cxxl
 */
class ModelUsageServiceImplTest {

    private ModelCallLogPort modelCallLogPort;

    private ModelUsageServiceImpl service;

    @BeforeEach
    void setUp() {
        modelCallLogPort = mock(ModelCallLogPort.class);
        service = new ModelUsageServiceImpl(modelCallLogPort);
    }

    @Test
    void summaryShouldParseTimeAndDictionaryValues() {
        when(modelCallLogPort.summarize(any())).thenReturn(new ModelCallLogSummary());
        ModelUsageQueryDto dto = new ModelUsageQueryDto();
        dto.setFrom("2026-10-01");
        dto.setTo("2026-10-02T12:30:00");
        dto.setTenantId(7L);
        dto.setCapability("chat");
        dto.setModel("  qwen-max  ");
        dto.setRouteReason("fallback_from");
        dto.setStatus("timeout");
        dto.setLimit(50);

        ModelCallLogSummary summary = service.summary(dto);

        assertNull(summary.getCalls());
        ModelCallLogQuery query = capturedSummaryQuery();
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), query.getFrom());
        assertEquals(LocalDateTime.of(2026, 10, 2, 12, 30), query.getTo());
        assertEquals(7L, query.getTenantId());
        assertEquals(ModelCapability.CHAT, query.getCapability());
        assertEquals("qwen-max", query.getModel());
        assertEquals(RouteReason.FALLBACK_FROM, query.getRouteReason());
        assertEquals(CallStatus.TIMEOUT, query.getStatus());
        assertEquals(50, query.getLimit());
    }

    @Test
    void callsShouldForwardToPort() {
        when(modelCallLogPort.listCalls(any())).thenReturn(List.of(new ModelCallLogView()));
        ModelUsageQueryDto dto = new ModelUsageQueryDto();
        dto.setFrom("2026-10-01T08:00:00");
        dto.setTo("2026-10-01T09:00:00");

        List<ModelCallLogView> views = service.calls(dto);

        assertEquals(1, views.size());
        ModelCallLogQuery query = capturedListQuery();
        assertEquals(LocalDateTime.of(2026, 10, 1, 8, 0), query.getFrom());
        assertNull(query.getCapability());
        assertNull(query.getStatus());
        assertNull(query.getRouteReason());
    }

    @Test
    void missingTimeRangeShouldBeRejected() {
        assertThrows(KnowledgeException.class, () -> service.summary(new ModelUsageQueryDto()));
        assertThrows(KnowledgeException.class, () -> service.calls(null));

        ModelUsageQueryDto blank = new ModelUsageQueryDto();
        blank.setFrom("  ");
        blank.setTo("2026-10-02");
        KnowledgeException error = assertThrows(KnowledgeException.class, () -> service.calls(blank));

        assertEquals(ErrorCode.PARAM_INVALID, error.getErrorCode());
        verify(modelCallLogPort, never()).listCalls(any());
        verify(modelCallLogPort, never()).summarize(any());
    }

    @Test
    void illegalTimeTextShouldBeRejected() {
        ModelUsageQueryDto dto = new ModelUsageQueryDto();
        dto.setFrom("2026/10/01");
        dto.setTo("2026-10-02");

        KnowledgeException error = assertThrows(KnowledgeException.class, () -> service.calls(dto));

        assertEquals(ErrorCode.PARAM_INVALID, error.getErrorCode());
        assertTrue(error.getMessage().contains("from"), error.getMessage());
        verify(modelCallLogPort, never()).listCalls(any());
    }

    @Test
    void illegalDictionaryValueShouldBeRejected() {
        ModelUsageQueryDto dto = new ModelUsageQueryDto();
        dto.setFrom("2026-10-01");
        dto.setTo("2026-10-02");
        dto.setCapability("VISION");

        KnowledgeException error = assertThrows(KnowledgeException.class, () -> service.summary(dto));

        assertEquals(ErrorCode.PARAM_INVALID, error.getErrorCode());
        assertTrue(error.getMessage().contains("能力"), error.getMessage());
        verify(modelCallLogPort, never()).summarize(any());
    }

    @Test
    void illegalStatusValueShouldBeRejected() {
        ModelUsageQueryDto dto = new ModelUsageQueryDto();
        dto.setFrom("2026-10-01");
        dto.setTo("2026-10-02");
        dto.setStatus("UNKNOWN");

        KnowledgeException error = assertThrows(KnowledgeException.class, () -> service.calls(dto));

        assertEquals(ErrorCode.PARAM_INVALID, error.getErrorCode());
        assertTrue(error.getMessage().contains("调用状态"), error.getMessage());
    }

    /** 取最近一次概览调用用的条件 */
    private ModelCallLogQuery capturedSummaryQuery() {
        ArgumentCaptor<ModelCallLogQuery> captor = ArgumentCaptor.forClass(ModelCallLogQuery.class);
        verify(modelCallLogPort).summarize(captor.capture());
        return captor.getValue();
    }

    /** 取最近一次明细查询用的条件 */
    private ModelCallLogQuery capturedListQuery() {
        ArgumentCaptor<ModelCallLogQuery> captor = ArgumentCaptor.forClass(ModelCallLogQuery.class);
        verify(modelCallLogPort).listCalls(captor.capture());
        return captor.getValue();
    }
}
