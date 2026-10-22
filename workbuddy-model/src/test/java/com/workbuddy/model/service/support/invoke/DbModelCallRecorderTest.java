package com.workbuddy.model.service.support.invoke;

import com.workbuddy.common.domain.entity.WbModelCall;
import com.workbuddy.common.domain.model.chat.TokenUsage;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.enums.model.RouteReason;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.model.ModelRef;
import com.workbuddy.model.db.WbModelCallDbService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调用明细记录器单测：字段落位与落库失败不影响主链路。
 */
@ExtendWith(MockitoExtension.class)
class DbModelCallRecorderTest {

    private static final ModelRef REF = new ModelRef("dashscope", "qwen-max");

    @Mock
    private WbModelCallDbService wbModelCallDbService;

    /** 成功尝试写入 usage 与状态（{@code shouldRecordSuccess}）。 */
    @Test
    void shouldRecordSuccess() {
        TokenUsage usage = new TokenUsage();
        usage.setInputTokens(12);
        usage.setOutputTokens(8);
        usage.setTotalTokens(20);

        new DbModelCallRecorder(wbModelCallDbService).record("req-1", ModelCapability.CHAT, REF, 2,
                true, null, 35, usage, RouteReason.FALLBACK_FROM);

        ArgumentCaptor<WbModelCall> captor = ArgumentCaptor.forClass(WbModelCall.class);
        verify(wbModelCallDbService).save(captor.capture());
        WbModelCall row = captor.getValue();
        assertEquals("req-1", row.getRequestId());
        assertEquals("CHAT", row.getCapability());
        assertEquals("dashscope", row.getVendorCode());
        assertEquals("qwen-max", row.getModelKey());
        assertEquals("SUCCESS", row.getStatus());
        assertEquals(2, row.getAttempt());
        assertEquals(35, row.getLatencyMs());
        assertEquals(12, row.getPromptTokens());
        assertEquals(8, row.getCompletionTokens());
        assertNull(row.getErrorCode());
        assertEquals("FALLBACK_FROM", row.getRouteReason());
    }

    /** 失败尝试写错误码；落库异常不抛出（{@code shouldRecordFailureAndSwallow}）。 */
    @Test
    void shouldRecordFailureAndSwallow() {
        when(wbModelCallDbService.save(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> new DbModelCallRecorder(wbModelCallDbService).record(
                "req-2", ModelCapability.CHAT, REF, 1, false, ErrorCode.MODEL_RATE_LIMITED,
                10, null, RouteReason.OK));
        ArgumentCaptor<WbModelCall> captor = ArgumentCaptor.forClass(WbModelCall.class);
        verify(wbModelCallDbService).save(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertEquals(ErrorCode.MODEL_RATE_LIMITED.getCode(), captor.getValue().getErrorCode());
    }
}
