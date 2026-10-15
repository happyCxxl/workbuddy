package com.workbuddy.biz.service.calllog;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.KbModelCallLogMapper;
import com.workbuddy.common.domain.entity.KbModelCallLog;
import com.workbuddy.common.domain.model.calllog.ModelCallLogEntry;
import com.workbuddy.common.domain.model.calllog.ModelCallLogQuery;
import com.workbuddy.common.domain.model.calllog.ModelCallLogSummary;
import com.workbuddy.common.domain.model.calllog.ModelCallLogView;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.enums.model.CallStatus;
import com.workbuddy.common.enums.model.RouteReason;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调用明细库表实现单测（Mapper 打桩 + SQL 断言）：
 * 写入的行组装（字典列取枚举名、用量与成本原样落库）、时间范围必填与维度过滤的 SQL 条件、
 * 明细取数上限与倒序、概览聚合的聚合列与未知占比。
 *
 * <p>边界：SQL 片段来自 MyBatis-Plus 的条件构造器，未在真实库验证（仓库无 DB 测试基建）。
 *
 * @author cxxl
 */
class DbModelCallLogServiceTest {

    /** 明细查询时间范围 */
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 1, 0, 0);

    private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 2, 0, 0);

    private KbModelCallLogMapper mapper;

    private DbModelCallLogService service;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), KbModelCallLog.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(KbModelCallLogMapper.class);
        service = new DbModelCallLogService();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    void recordShouldInsertRowWithDictionaryValuesAndUsage() {
        when(mapper.insert(any(KbModelCallLog.class))).thenReturn(1);

        service.record(entry());

        ArgumentCaptor<KbModelCallLog> captor = ArgumentCaptor.forClass(KbModelCallLog.class);
        verify(mapper).insert(captor.capture());
        KbModelCallLog row = captor.getValue();
        assertEquals("CHAT", row.getCapability());
        assertEquals("dashscope", row.getVendor());
        assertEquals("qwen-max", row.getModel());
        assertEquals("2026-01", row.getModelVersion());
        assertEquals("SUCCESS", row.getStatus());
        assertEquals("FALLBACK_FROM", row.getRouteReason());
        assertNull(row.getErrorCode());
        assertEquals(0, row.getUsageUnknown());
        assertEquals(1000L, row.getPromptTokens());
        assertEquals(500L, row.getCompletionTokens());
        assertEquals(1500L, row.getTotalTokens());
        assertEquals(new BigDecimal("0.006000"), row.getCost());
        assertEquals(new BigDecimal("2.000000"), row.getUnitPriceIn());
        assertEquals(new BigDecimal("8.000000"), row.getUnitPriceOut());
        assertEquals("CNY", row.getCurrency());
        assertEquals(120, row.getLatencyMs());
        assertEquals(2, row.getRetryCount());
        assertEquals(0, row.getCacheHit());
    }

    @Test
    void recordShouldKeepUnknownUsageAsNull() {
        when(mapper.insert(any(KbModelCallLog.class))).thenReturn(1);
        ModelCallLogEntry entry = new ModelCallLogEntry();
        entry.setCapability(ModelCapability.CHAT);
        entry.setVendor("dashscope");
        entry.setModel("qwen-max");
        entry.setStatus(CallStatus.SUCCESS);
        entry.setUsageUnknown(1);
        entry.setRetryCount(0);
        entry.setCacheHit(0);

        service.record(entry);

        ArgumentCaptor<KbModelCallLog> captor = ArgumentCaptor.forClass(KbModelCallLog.class);
        verify(mapper).insert(captor.capture());
        KbModelCallLog row = captor.getValue();
        assertEquals(1, row.getUsageUnknown());
        assertNull(row.getPromptTokens());
        assertNull(row.getTotalTokens());
        assertNull(row.getCost());
        assertNull(row.getLatencyMs());
    }

    @Test
    void recordShouldRejectNullEntry() {
        KnowledgeException error = assertThrows(KnowledgeException.class, () -> service.record(null));

        assertEquals(ErrorCode.PARAM_INVALID, error.getErrorCode());
        verify(mapper, never()).insert(any(KbModelCallLog.class));
    }

    @Test
    void listCallsShouldRejectMissingTimeRange() {
        assertThrows(KnowledgeException.class, () -> service.listCalls(new ModelCallLogQuery()));
        assertThrows(KnowledgeException.class, () -> service.listCalls(query(FROM, null)));
        assertThrows(KnowledgeException.class, () -> service.listCalls(query(null, TO)));
        assertThrows(KnowledgeException.class, () -> service.listCalls(null));

        verify(mapper, never()).selectList(any());
    }

    @Test
    void listCallsShouldRejectInvertedRange() {
        KnowledgeException error = assertThrows(KnowledgeException.class, () -> service.listCalls(query(TO, FROM)));

        assertEquals(ErrorCode.PARAM_INVALID, error.getErrorCode());
        verify(mapper, never()).selectList(any());
    }

    @Test
    void listCallsShouldFilterByTimeAndDimensionsInSql() {
        when(mapper.selectList(any())).thenReturn(List.of(row()));
        ModelCallLogQuery query = query(FROM, TO);
        query.setTenantId(7L);
        query.setCapability(ModelCapability.CHAT);
        query.setModel("qwen-max");
        query.setRouteReason(RouteReason.FALLBACK_FROM);
        query.setStatus(CallStatus.SUCCESS);

        List<ModelCallLogView> views = service.listCalls(query);

        String sql = capturedListSql();
        assertTrue(sql.contains("create_time"), sql);
        assertTrue(sql.contains("tenant_id"), sql);
        assertTrue(sql.contains("capability"), sql);
        assertTrue(sql.contains("model"), sql);
        assertTrue(sql.contains("route_reason"), sql);
        assertTrue(sql.contains("status"), sql);
        assertTrue(sql.contains("ORDER BY create_time DESC"), sql);
        assertTrue(sql.contains("LIMIT " + ModelCallLogQuery.DEFAULT_LIMIT), sql);
        assertEquals(1, views.size());
        ModelCallLogView view = views.getFirst();
        assertEquals(11L, view.getId());
        assertEquals(ModelCapability.CHAT, view.getCapability());
        assertEquals(CallStatus.SUCCESS, view.getStatus());
        assertEquals(RouteReason.FALLBACK_FROM, view.getRouteReason());
        assertEquals(FROM, view.getCreateTime());
        assertEquals(new BigDecimal("0.006000"), view.getCost());
    }

    @Test
    void listCallsShouldSkipBlankDimensionFilters() {
        when(mapper.selectList(any())).thenReturn(List.of());
        ModelCallLogQuery query = query(FROM, TO);
        query.setModel("  ");

        service.listCalls(query);

        String sql = capturedListSql();
        assertTrue(sql.contains("create_time"), sql);
        assertFalse(sql.contains("tenant_id"), sql);
        assertFalse(sql.contains("route_reason"), sql);
    }

    @Test
    void listCallsShouldCapLimit() {
        when(mapper.selectList(any())).thenReturn(List.of());
        ModelCallLogQuery capped = query(FROM, TO);
        capped.setLimit(99999);
        service.listCalls(capped);
        ModelCallLogQuery small = query(FROM, TO);
        small.setLimit(20);
        service.listCalls(small);

        List<String> segments = capturedListSqlSegments();
        assertTrue(segments.get(0).contains("LIMIT " + ModelCallLogQuery.MAX_LIMIT), segments.get(0));
        assertTrue(segments.get(1).contains("LIMIT 20"), segments.get(1));
    }

    @Test
    void summarizeShouldAggregateOnDetailTable() {
        when(mapper.selectMaps(any())).thenReturn(List.of(aggregateRow()));

        ModelCallLogSummary summary = service.summarize(query(FROM, TO));

        QueryWrapper<KbModelCallLog> wrapper = capturedSummaryWrapper();
        String select = wrapper.getSqlSelect();
        assertTrue(select.contains("COUNT(*) AS calls"), select);
        assertTrue(select.contains("SUM(CASE WHEN status = 'SUCCESS'"), select);
        assertTrue(select.contains("AS unknownCalls"), select);
        assertTrue(select.contains("SUM(total_tokens) AS totalTokens"), select);
        assertTrue(select.contains("SUM(cost) AS costTotal"), select);
        String where = wrapper.getSqlSegment();
        assertTrue(where.contains("create_time"), where);
        assertEquals(4L, summary.getCalls());
        assertEquals(3L, summary.getSuccessCalls());
        assertEquals(1L, summary.getFailedCalls());
        assertEquals(1L, summary.getTimeoutCalls());
        assertEquals(1L, summary.getRateLimitedCalls());
        assertEquals(1L, summary.getUnknownCalls());
        assertEquals(new BigDecimal("0.2500"), summary.getUnknownRatio());
        assertEquals(1500L, summary.getTotalTokens());
        assertEquals(new BigDecimal("0.006000"), summary.getCostTotal());
    }

    @Test
    void summarizeShouldApplyDimensionFilters() {
        when(mapper.selectMaps(any())).thenReturn(List.of(aggregateRow()));
        ModelCallLogQuery query = query(FROM, TO);
        query.setTenantId(7L);
        query.setCapability(ModelCapability.CHAT);
        query.setModel("qwen-max");
        query.setRouteReason(RouteReason.OK);
        query.setStatus(CallStatus.TIMEOUT);

        service.summarize(query);

        String where = capturedSummaryWrapper().getSqlSegment();
        assertTrue(where.contains("tenant_id"), where);
        assertTrue(where.contains("capability"), where);
        assertTrue(where.contains("model"), where);
        assertTrue(where.contains("route_reason"), where);
        assertTrue(where.contains("status"), where);
    }

    @Test
    void summarizeWithUnknownRatioShouldRoundToFourDecimals() {
        Map<String, Object> row = aggregateRow();
        row.put("calls", 3L);
        row.put("unknownCalls", 1L);
        when(mapper.selectMaps(any())).thenReturn(List.of(row));

        ModelCallLogSummary summary = service.summarize(query(FROM, TO));

        assertEquals(new BigDecimal("0.3333"), summary.getUnknownRatio());
    }

    @Test
    void summarizeWithoutRowsShouldKeepCountsZeroAndTotalsNull() {
        when(mapper.selectMaps(any())).thenReturn(List.of());

        ModelCallLogSummary summary = service.summarize(query(FROM, TO));

        assertEquals(0L, summary.getCalls());
        assertEquals(0L, summary.getUnknownCalls());
        assertNull(summary.getUnknownRatio());
        assertNull(summary.getTotalTokens());
        assertNull(summary.getCostTotal());
    }

    @Test
    void summarizeWithAllUnknownUsageShouldKeepTotalsNull() {
        Map<String, Object> row = aggregateRow();
        row.put("totalTokens", null);
        row.put("costTotal", null);
        row.put("successCalls", null);
        when(mapper.selectMaps(any())).thenReturn(List.of(row));

        ModelCallLogSummary summary = service.summarize(query(FROM, TO));

        assertEquals(4L, summary.getCalls());
        assertEquals(0L, summary.getSuccessCalls());
        assertNull(summary.getTotalTokens());
        assertNull(summary.getCostTotal());
        assertEquals(new BigDecimal("0.2500"), summary.getUnknownRatio());
    }

    @Test
    void summarizeShouldRejectMissingTimeRange() {
        assertThrows(KnowledgeException.class, () -> service.summarize(new ModelCallLogQuery()));

        verify(mapper, never()).selectMaps(any());
    }

    /** 取最近一次明细查询用的条件 */
    private String capturedListSql() {
        return capturedListSqlSegments().getLast();
    }

    /** 取全部明细查询用的条件片段（按调用顺序） */
    private List<String> capturedListSqlSegments() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<KbModelCallLog>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, atLeastOnce()).selectList(captor.capture());
        List<String> segments = new ArrayList<>();
        for (Wrapper<KbModelCallLog> wrapper : captor.getAllValues()) {
            assertNotNull(wrapper);
            segments.add(wrapper.getSqlSegment());
        }
        return segments;
    }

    /** 取最近一次概览聚合用的条件 */
    private QueryWrapper<KbModelCallLog> capturedSummaryWrapper() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<KbModelCallLog>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectMaps(captor.capture());
        return (QueryWrapper<KbModelCallLog>) captor.getValue();
    }

    /** 查询条件：时间范围按入参给值 */
    private ModelCallLogQuery query(LocalDateTime from, LocalDateTime to) {
        ModelCallLogQuery query = new ModelCallLogQuery();
        query.setFrom(from);
        query.setTo(to);
        return query;
    }

    /** 明细入参：一次成功的对话调用（含用量、单价快照与成本） */
    private ModelCallLogEntry entry() {
        ModelCallLogEntry entry = new ModelCallLogEntry();
        entry.setCapability(ModelCapability.CHAT);
        entry.setVendor("dashscope");
        entry.setModel("qwen-max");
        entry.setModelVersion("2026-01");
        entry.setStatus(CallStatus.SUCCESS);
        entry.setRouteReason(RouteReason.FALLBACK_FROM);
        entry.setUsageUnknown(0);
        entry.setPromptTokens(1000L);
        entry.setCompletionTokens(500L);
        entry.setTotalTokens(1500L);
        entry.setUnitPriceIn(new BigDecimal("2.000000"));
        entry.setUnitPriceOut(new BigDecimal("8.000000"));
        entry.setCurrency("CNY");
        entry.setCost(new BigDecimal("0.006000"));
        entry.setLatencyMs(120);
        entry.setRetryCount(2);
        entry.setCacheHit(0);
        return entry;
    }

    /** 库表行：与明细入参同值 */
    private KbModelCallLog row() {
        KbModelCallLog row = new KbModelCallLog();
        row.setId(11L);
        row.setCapability("CHAT");
        row.setVendor("dashscope");
        row.setModel("qwen-max");
        row.setStatus("SUCCESS");
        row.setRouteReason("FALLBACK_FROM");
        row.setUsageUnknown(0);
        row.setTotalTokens(1500L);
        row.setCost(new BigDecimal("0.006000"));
        row.setCreateTime(FROM);
        return row;
    }

    /** 聚合行：四行调用里三成两败、一条未知用量（列名与聚合别名一致） */
    private Map<String, Object> aggregateRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("calls", 4L);
        row.put("successCalls", 3L);
        row.put("failedCalls", 1L);
        row.put("timeoutCalls", 1L);
        row.put("rateLimitedCalls", 1L);
        row.put("unknownCalls", 1L);
        row.put("totalTokens", 1500L);
        row.put("costTotal", new BigDecimal("0.006000"));
        return row;
    }
}
