package com.workbuddy.model.service.support.invoke;

import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.domain.model.chat.ChatRequest;
import com.workbuddy.common.domain.model.chat.ChatResult;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.model.ModelAdapter;
import com.workbuddy.model.ModelRef;
import com.workbuddy.model.config.ModelRouteProperties;
import com.workbuddy.model.service.support.capacity.ModelCapacityGuard;
import com.workbuddy.model.service.support.capacity.ModelCooldownStore;
import com.workbuddy.model.service.support.route.ModelCircuitBreakers;
import com.workbuddy.model.service.support.route.ModelRouter;
import com.workbuddy.model.service.support.route.RouteStateStore;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调用器单测：选路、容量许可、可降级换模型、不可降级立即失败与无候选。
 */
@ExtendWith(MockitoExtension.class)
class ModelInvokerTest {

    private static final ModelRef REF_A = new ModelRef("dashscope", "a");

    private static final ModelRef REF_B = new ModelRef("dashscope", "b");

    @Mock
    private ModelAdapter modelAdapter;

    @Mock
    private ModelRouter modelRouter;

    @Mock
    private ModelCapacityGuard modelCapacityGuard;

    @Mock
    private ModelCircuitBreakers modelCircuitBreakers;

    @Mock
    private ModelCooldownStore modelCooldownStore;

    @Mock
    private DbModelCallRecorder modelCallRecorder;

    @Mock
    private RouteStateStore routeStateStore;

    private ModelInvoker invoker;

    private ModelRouteProperties routeProperties;

    @BeforeEach
    void setUp() {
        routeProperties = new ModelRouteProperties();
        invoker = new ModelInvoker(modelAdapter, modelRouter, modelCapacityGuard,
                modelCircuitBreakers, modelCooldownStore, new ModelErrorClassifier(),
                routeProperties, routeStateStore, modelCallRecorder);
        lenient().when(modelCapacityGuard.tryAcquire(any(), any(), any())).thenReturn(true);
        lenient().when(modelCircuitBreakers.tryAcquire(any())).thenReturn(true);
    }

    private ModelDescriptor model(String modelKey) {
        ModelDescriptor model = new ModelDescriptor();
        model.setVendorCode("dashscope");
        model.setModelKey(modelKey);
        model.setCapabilities(Set.of(ModelCapability.CHAT));
        model.setStatus("ENABLED");
        model.setRpmLimit(60);
        model.setTpmLimit(100000);
        return model;
    }

    private ChatRequest request() {
        return new ChatRequest();
    }

    private ChatResult result(String content) {
        ChatResult result = new ChatResult();
        result.setContent(content);
        return result;
    }

    /** 首个候选成功：调用一次适配器并记录延迟（{@code shouldCallFirstCandidate}）。 */
    @Test
    void shouldCallFirstCandidate() {
        ModelDescriptor modelA = model("a");
        ChatRequest request = request();
        ChatResult expected = result("ok");
        when(modelRouter.select(eq(ModelCapability.CHAT), isNull(), anySet())).thenReturn(modelA);
        when(modelAdapter.chat(modelA, request)).thenReturn(expected);

        ChatResult actual = invoker.chat(request);

        assertSame(expected, actual);
        verify(modelAdapter).chat(modelA, request);
        verify(routeStateStore).recordLatency(eq(REF_A), anyLong());
    }

    /** 可降级失败换下一个候选（{@code shouldFallbackToNextCandidate}）。 */
    @Test
    void shouldFallbackToNextCandidate() {
        ModelDescriptor modelA = model("a");
        ModelDescriptor modelB = model("b");
        ChatRequest request = request();
        ChatResult expected = result("ok-b");
        when(modelRouter.select(eq(ModelCapability.CHAT), isNull(), anySet()))
                .thenReturn(modelA, modelB);
        when(modelAdapter.chat(modelA, request)).thenThrow(new RateLimitException("429"));
        when(modelAdapter.chat(modelB, request)).thenReturn(expected);

        ChatResult actual = invoker.chat(request);

        assertSame(expected, actual);
        verify(modelAdapter).chat(modelA, request);
        verify(modelAdapter).chat(modelB, request);
    }

    /** 不可降级失败立即抛出，不再尝试下一个（{@code shouldStopOnNonFallbackError}）。 */
    @Test
    void shouldStopOnNonFallbackError() {
        ModelDescriptor modelA = model("a");
        ChatRequest request = request();
        when(modelRouter.select(eq(ModelCapability.CHAT), isNull(), anySet()))
                .thenReturn(modelA, model("b"));
        when(modelAdapter.chat(modelA, request)).thenThrow(new AuthenticationException("401"));

        WorkbuddyException error = assertThrows(WorkbuddyException.class, () -> invoker.chat(request));

        assertEquals(ErrorCode.MODEL_AUTH_FAILED, error.getErrorCode());
        verify(modelRouter, times(1)).select(eq(ModelCapability.CHAT), isNull(), anySet());
    }

    /** 全部候选可降级失败：抛最后一次错误（{@code shouldThrowLastErrorWhenAllFallback}）。 */
    @Test
    void shouldThrowLastErrorWhenAllFallback() {
        routeProperties.setMaxAttempts(2);
        ChatRequest request = request();
        when(modelRouter.select(eq(ModelCapability.CHAT), isNull(), anySet()))
                .thenReturn(model("a"), model("b"));
        when(modelAdapter.chat(any(), eq(request))).thenThrow(new RateLimitException("429"));

        WorkbuddyException error = assertThrows(WorkbuddyException.class, () -> invoker.chat(request));

        assertEquals(ErrorCode.MODEL_RATE_LIMITED, error.getErrorCode());
        verify(modelAdapter, times(2)).chat(any(), eq(request));
    }

    /** 无候选：按 40464 拒绝，不发起调用（{@code shouldRejectWhenNoCandidate}）。 */
    @Test
    void shouldRejectWhenNoCandidate() {
        when(modelRouter.select(eq(ModelCapability.CHAT), isNull(), anySet())).thenReturn(null);

        WorkbuddyException error = assertThrows(WorkbuddyException.class, () -> invoker.chat(request()));

        assertEquals(ErrorCode.MODEL_NOT_FOUND, error.getErrorCode());
        verify(modelAdapter, never()).chat(any(), any());
    }

    /** 容量不足跳过当前候选，换下一个（{@code shouldSkipCapacityRejected}）。 */
    @Test
    void shouldSkipCapacityRejected() {
        ModelDescriptor modelA = model("a");
        ModelDescriptor modelB = model("b");
        ChatRequest request = request();
        ChatResult expected = result("ok-b");
        when(modelRouter.select(eq(ModelCapability.CHAT), isNull(), anySet()))
                .thenReturn(modelA, modelB);
        when(modelCapacityGuard.tryAcquire(eq(REF_A), any(), any())).thenReturn(false);
        when(modelCapacityGuard.tryAcquire(eq(REF_B), any(), any())).thenReturn(true);
        when(modelAdapter.chat(modelB, request)).thenReturn(expected);

        ChatResult actual = invoker.chat(request);

        assertSame(expected, actual);
        verify(modelAdapter, never()).chat(eq(modelA), any());
        verify(modelAdapter).chat(modelB, request);
    }

    /** 全部候选容量不足：按 40470 收口（{@code shouldRejectWhenCapacityExhausted}）。 */
    @Test
    void shouldRejectWhenCapacityExhausted() {
        routeProperties.setMaxAttempts(2);
        when(modelRouter.select(eq(ModelCapability.CHAT), isNull(), anySet()))
                .thenReturn(model("a"), model("b"));
        when(modelCapacityGuard.tryAcquire(any(), any(), any())).thenReturn(false);

        WorkbuddyException error = assertThrows(WorkbuddyException.class, () -> invoker.chat(request()));

        assertEquals(ErrorCode.MODEL_UNAVAILABLE, error.getErrorCode());
        verify(modelAdapter, never()).chat(any(), any());
    }
}
