package com.workbuddy.model.calllog;

import com.workbuddy.common.domain.model.ModelUsage;
import com.workbuddy.common.domain.model.calllog.ModelCallLogEntry;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.model.CallStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.model.catalog.ModelCatalogPort;
import com.workbuddy.model.listener.ModelCallTrace;
import com.workbuddy.model.support.InMemoryModelCallLogPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 调用明细组装单测（明细端口与目录打桩）：
 * 用量与成本的四种形态（齐全 / 缺 usage / 目录无价格 / 未收录）、单价快照、状态映射、
 * 重试与延迟取自调用上下文、落库失败不影响调用结果。
 *
 * @author cxxl
 */
class ModelCallLogRecorderTest {

    private static final String VENDOR = "dashscope";

    private static final String MODEL = "qwen-max";

    private InMemoryModelCallLogPort logPort;

    private ModelCatalogPort catalog;

    private ModelCallLogRecorder recorder;

    @BeforeEach
    void setUp() {
        logPort = new InMemoryModelCallLogPort();
        catalog = mock(ModelCatalogPort.class);
        recorder = new ModelCallLogRecorder(logPort, catalog);
    }

    @Test
    void successWithUsageAndPricesShouldWriteTokensPricesAndCost() {
        when(catalog.findByVendorAndModelKey(VENDOR, MODEL)).thenReturn(descriptor("2.000000", "8.000000", "CNY"));

        recorder.recordSuccess(VENDOR, MODEL, ModelCapability.CHAT, trace(usage(1000, 500, 1500), 3L),
                "qwen-max-2026", "2026-01", null);

        ModelCallLogEntry entry = logPort.lastEntry();
        assertEquals(1, logPort.entries().size());
        assertEquals(ModelCapability.CHAT, entry.getCapability());
        assertEquals(VENDOR, entry.getVendor());
        assertEquals("qwen-max-2026", entry.getModel());
        assertEquals("2026-01", entry.getModelVersion());
        assertEquals(CallStatus.SUCCESS, entry.getStatus());
        assertNull(entry.getErrorCode());
        assertEquals(0, entry.getUsageUnknown());
        assertEquals(1000L, entry.getPromptTokens());
        assertEquals(500L, entry.getCompletionTokens());
        assertEquals(1500L, entry.getTotalTokens());
        // 1000 × 2.000000 / 1e6 + 500 × 8.000000 / 1e6 = 0.006
        assertEquals(new BigDecimal("0.006000"), entry.getCost());
        assertEquals(new BigDecimal("2.000000"), entry.getUnitPriceIn());
        assertEquals(new BigDecimal("8.000000"), entry.getUnitPriceOut());
        assertEquals("CNY", entry.getCurrency());
        assertLatencyAtLeast(entry, 3);
        assertEquals(0, entry.getRetryCount());
        assertEquals(0, entry.getCacheHit());
    }

    @Test
    void successWithoutUsageShouldWriteNullsWithUnknownFlag() {
        when(catalog.findByVendorAndModelKey(VENDOR, MODEL)).thenReturn(descriptor("2.000000", "8.000000", "CNY"));

        recorder.recordSuccess(VENDOR, MODEL, ModelCapability.CHAT, trace(null, 1L), MODEL, null, null);

        ModelCallLogEntry entry = logPort.lastEntry();
        assertEquals(1, entry.getUsageUnknown());
        assertNull(entry.getPromptTokens());
        assertNull(entry.getCompletionTokens());
        assertNull(entry.getTotalTokens());
        assertNull(entry.getCost());
        assertNull(entry.getUnitPriceIn());
        assertNull(entry.getUnitPriceOut());
        assertNull(entry.getCurrency());
        assertEquals(CallStatus.SUCCESS, entry.getStatus());
    }

    @Test
    void successWithEmptyUsageCountsShouldAlsoBeUnknown() {
        recorder.recordSuccess(VENDOR, MODEL, ModelCapability.CHAT, trace(usage(null, null, null), 1L), MODEL, null,
                null);

        ModelCallLogEntry entry = logPort.lastEntry();
        assertEquals(1, entry.getUsageUnknown());
        assertNull(entry.getTotalTokens());
        assertNull(entry.getCost());
    }

    @Test
    void successWithoutCatalogPriceShouldKeepCostEmpty() {
        when(catalog.findByVendorAndModelKey(VENDOR, MODEL)).thenReturn(descriptor(null, null, "CNY"));

        recorder.recordSuccess(VENDOR, MODEL, ModelCapability.CHAT, trace(usage(1000, 500, 1500), 1L), MODEL, null,
                null);

        ModelCallLogEntry entry = logPort.lastEntry();
        assertEquals(0, entry.getUsageUnknown());
        assertEquals(1000L, entry.getPromptTokens());
        assertNull(entry.getCost());
        assertNull(entry.getUnitPriceIn());
        assertNull(entry.getUnitPriceOut());
        assertNull(entry.getCurrency());
    }

    @Test
    void successWithModelAbsentFromCatalogShouldKeepCostEmpty() {
        when(catalog.findByVendorAndModelKey(VENDOR, MODEL)).thenReturn(null);

        recorder.recordSuccess(VENDOR, MODEL, ModelCapability.CHAT, trace(usage(1000, 500, 1500), 1L), MODEL, null,
                null);

        ModelCallLogEntry entry = logPort.lastEntry();
        assertEquals(1000L, entry.getPromptTokens());
        assertNull(entry.getCost());
    }

    @Test
    void catalogFailureShouldKeepCostEmptyWithoutBreakingTheCall() {
        when(catalog.findByVendorAndModelKey(anyString(), anyString())).thenThrow(new IllegalStateException("目录不可用"));

        assertDoesNotThrow(() -> recorder.recordSuccess(VENDOR, MODEL, ModelCapability.CHAT,
                trace(usage(1000, 500, 1500), 1L), MODEL, null, null));

        ModelCallLogEntry entry = logPort.lastEntry();
        assertEquals(1000L, entry.getPromptTokens());
        assertNull(entry.getCost());
    }

    @Test
    void usageFromResultShouldBeUsedWhenTraceHasNone() {
        ModelUsage usage = usage(1000, 500, 1500);

        recorder.recordSuccess(VENDOR, MODEL, ModelCapability.CHAT, trace(null, 1L), MODEL, null, usage);

        ModelCallLogEntry entry = logPort.lastEntry();
        assertEquals(0, entry.getUsageUnknown());
        assertEquals(1500L, entry.getTotalTokens());
    }

    @Test
    void blankModelNameShouldFallBackToRequestedModel() {
        recorder.recordSuccess(VENDOR, MODEL, ModelCapability.CHAT, trace(usage(1, 1, 2), 1L), "  ", null, null);

        assertEquals(MODEL, logPort.lastEntry().getModel());
        assertEquals(CallStatus.SUCCESS, logPort.lastEntry().getStatus());
    }

    @Test
    void retryCountAndLatencyShouldComeFromTrace() {
        ModelCallTrace trace = trace(usage(1, 1, 2), 3L);
        trace.increaseAttempts();
        trace.increaseAttempts();
        trace.increaseAttempts();

        recorder.recordSuccess(VENDOR, MODEL, ModelCapability.CHAT, trace, MODEL, null, null);

        ModelCallLogEntry entry = logPort.lastEntry();
        assertEquals(2, entry.getRetryCount());
        assertLatencyAtLeast(entry, 3);
    }

    @Test
    void failureShouldCarryStatusAndErrorCode() {
        ModelCallTrace trace = trace(null, 2L);
        trace.increaseAttempts();

        recorder.recordFailure(VENDOR, MODEL, ModelCapability.CHAT, trace, ErrorCode.MODEL_SERVER_ERROR);

        ModelCallLogEntry entry = logPort.lastEntry();
        assertEquals(CallStatus.FAILED, entry.getStatus());
        assertEquals("MODEL_SERVER_ERROR", entry.getErrorCode());
        assertEquals(1, entry.getUsageUnknown());
        assertEquals(0, entry.getRetryCount());
        assertEquals(MODEL, entry.getModel());
        assertLatencyAtLeast(entry, 2);
    }

    @Test
    void timeoutAndRateLimitedShouldMapToTheirOwnStatus() {
        recorder.recordFailure(VENDOR, MODEL, ModelCapability.CHAT, trace(null, 1L), ErrorCode.MODEL_TIMEOUT);
        assertEquals(CallStatus.TIMEOUT, logPort.lastEntry().getStatus());
        assertEquals("MODEL_TIMEOUT", logPort.lastEntry().getErrorCode());

        recorder.recordFailure(VENDOR, MODEL, ModelCapability.CHAT, trace(null, 1L), ErrorCode.MODEL_RATE_LIMITED);
        assertEquals(CallStatus.RATE_LIMITED, logPort.lastEntry().getStatus());
        assertEquals("MODEL_RATE_LIMITED", logPort.lastEntry().getErrorCode());
    }

    @Test
    void failedCallWithUsageShouldStillRecordTokens() {
        recorder.recordFailure(VENDOR, MODEL, ModelCapability.CHAT, trace(usage(10, 5, 15), 1L),
                ErrorCode.MODEL_OUTPUT_INVALID);

        ModelCallLogEntry entry = logPort.lastEntry();
        assertEquals(CallStatus.FAILED, entry.getStatus());
        assertEquals(0, entry.getUsageUnknown());
        assertEquals(15L, entry.getTotalTokens());
    }

    @Test
    void externalServiceCapabilitiesShouldAlwaysBeUnknown() {
        for (ModelCapability capability : new ModelCapability[]{ModelCapability.OCR, ModelCapability.LAYOUT,
                ModelCapability.TABLE_STRUCTURE}) {
            recorder.recordSuccess(VENDOR, MODEL, capability, trace(usage(10, 5, 15), 1L), MODEL, null, null);

            ModelCallLogEntry entry = logPort.lastEntry();
            assertEquals(capability, entry.getCapability());
            assertEquals(1, entry.getUsageUnknown(), "外部服务能力按未知计量：" + capability);
            assertNull(entry.getPromptTokens());
            assertNull(entry.getTotalTokens());
            assertNull(entry.getCost());
        }
    }

    @Test
    void embeddingUsageShouldRecordTokensWithoutTokenCost() {
        when(catalog.findByVendorAndModelKey(VENDOR, MODEL)).thenReturn(descriptor("2.000000", null, "CNY"));

        recorder.recordSuccess(VENDOR, MODEL, ModelCapability.EMBEDDING, trace(usage(120, null, 120), 1L), MODEL, null,
                null);

        ModelCallLogEntry entry = logPort.lastEntry();
        assertEquals(0, entry.getUsageUnknown());
        assertEquals(120L, entry.getPromptTokens());
        assertNull(entry.getCompletionTokens());
        assertNull(entry.getCost());
        assertNull(entry.getUnitPriceIn());
    }

    @Test
    void logPortFailureShouldNotBreakTheCall() {
        when(catalog.findByVendorAndModelKey(anyString(), anyString())).thenReturn(descriptor("1.0", "1.0", "CNY"));
        logPort.withFailure(new IllegalStateException("库不可用"));

        assertDoesNotThrow(() -> recorder.recordSuccess(VENDOR, MODEL, ModelCapability.CHAT,
                trace(usage(1, 1, 2), 1L), MODEL, null, null));
        assertDoesNotThrow(() -> recorder.recordFailure(VENDOR, MODEL, ModelCapability.CHAT, trace(null, 1L),
                ErrorCode.MODEL_SERVER_ERROR));

        assertTrue(logPort.entries().isEmpty());
    }

    @Test
    void missingErrorCodeShouldFallBackToTraceErrorCode() {
        ModelCallTrace trace = trace(null, 1L);
        trace.setErrorCode(ErrorCode.MODEL_UNREACHABLE);

        recorder.recordFailure(VENDOR, MODEL, ModelCapability.CHAT, trace, null);

        ModelCallLogEntry entry = logPort.lastEntry();
        assertEquals(CallStatus.FAILED, entry.getStatus());
        assertEquals("MODEL_UNREACHABLE", entry.getErrorCode());
    }

    /** 调用上下文：用量按入参设定，延迟经"记开始 + 等待 + 记差值"写入 */
    private ModelCallTrace trace(ModelUsage usage, Long latencyMs) {
        ModelCallTrace trace = new ModelCallTrace();
        trace.setUsage(usage);
        if (latencyMs != null) {
            trace.markRequestStart();
            sleep(latencyMs);
            trace.markLatency();
        }
        return trace;
    }

    /** 用量：三项按入参给值 */
    private ModelUsage usage(Integer prompt, Integer completion, Integer total) {
        ModelUsage usage = new ModelUsage();
        usage.setPromptTokens(prompt);
        usage.setCompletionTokens(completion);
        usage.setTotalTokens(total);
        return usage;
    }

    /** 目录项：价格与币种按入参给值，能力为对话 */
    private ModelDescriptor descriptor(String priceIn, String priceOut, String currency) {
        ModelDescriptor descriptor = new ModelDescriptor();
        descriptor.setVendorCode(VENDOR);
        descriptor.setModelKey(MODEL);
        descriptor.setCapabilities(Set.of(ModelCapability.CHAT));
        descriptor.setPriceInPerM(priceIn == null ? null : new BigDecimal(priceIn));
        descriptor.setPriceOutPerM(priceOut == null ? null : new BigDecimal(priceOut));
        descriptor.setCurrency(currency);
        return descriptor;
    }

    /** 延迟断言：等待过若干毫秒，落库值不应小于该值 */
    private void assertLatencyAtLeast(ModelCallLogEntry entry, int atLeast) {
        assertTrue(entry.getLatencyMs() != null && entry.getLatencyMs() >= atLeast,
                "延迟应取自调用上下文，实际：" + entry.getLatencyMs());
    }

    /** 等待若干毫秒，让调用上下文里的延迟落在期望区间 */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
