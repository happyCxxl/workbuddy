package com.workbuddy.model.invoke;

import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.model.invoke.pipeline.AttemptStage;
import com.workbuddy.model.invoke.pipeline.CallContext;
import com.workbuddy.model.invoke.pipeline.CandidateStage;
import com.workbuddy.model.invoke.pipeline.ModelCall;
import com.workbuddy.model.invoke.pipeline.RequestStage;
import com.workbuddy.model.invoke.pipeline.RouteStage;
import com.workbuddy.model.route.ModelRouter;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * 调用管线单测：候选执行、失败降级、不可降级与无候选。
 */
@ExtendWith(MockitoExtension.class)
class ModelInvokerTest {

    @Mock
    private ModelRouter modelRouter;

    private ModelInvoker invoker;

    @BeforeEach
    void setUp() {
        invoker = new ModelInvoker(new RequestStage(), new CandidateStage(modelRouter), new RouteStage(),
                new AttemptStage(new ModelErrorClassifier()));
    }

    private ModelDescriptor model(String modelKey) {
        ModelDescriptor model = new ModelDescriptor();
        model.setVendorCode("dashscope");
        model.setModelKey(modelKey);
        model.setCapabilities(java.util.Set.of(ModelCapability.CHAT));
        model.setStatus("ENABLED");
        model.setRpmLimit(60);
        model.setTpmLimit(100000);
        return model;
    }

    private CallContext context(ModelCall<String> call) {
        CallContext context = new CallContext();
        context.setCapability(ModelCapability.CHAT);
        context.setCall(call);
        return context;
    }

    /** 首个候选成功：补齐请求 ID 并返回结果（{@code shouldCallFirstCandidate}）。 */
    @Test
    void shouldCallFirstCandidate() {
        when(modelRouter.candidates(ModelCapability.CHAT, null))
                .thenReturn(List.of(model("a"), model("b")));

        String result = invoker.invoke(context(candidate -> "ok-" + candidate.getModelKey()));

        assertEquals("ok-a", result);
    }

    /** 可降级失败换下一个候选（{@code shouldFallbackToNextCandidate}）。 */
    @Test
    void shouldFallbackToNextCandidate() {
        when(modelRouter.candidates(ModelCapability.CHAT, null))
                .thenReturn(List.of(model("a"), model("b")));
        CallContext context = context(candidate -> {
            if ("a".equals(candidate.getModelKey())) {
                throw new RateLimitException("429");
            }
            return "ok-" + candidate.getModelKey();
        });

        String result = invoker.invoke(context);

        assertEquals("ok-b", result);
        assertEquals(2, context.getAttempt());
        assertNotNull(context.getLastError());
    }

    /** 不可降级失败立即抛出，不再尝试下一个（{@code shouldStopOnNonFallbackError}）。 */
    @Test
    void shouldStopOnNonFallbackError() {
        when(modelRouter.candidates(ModelCapability.CHAT, null))
                .thenReturn(List.of(model("a"), model("b")));
        CallContext context = context(candidate -> {
            throw new AuthenticationException("401");
        });

        KnowledgeException error = assertThrows(KnowledgeException.class, () -> invoker.invoke(context));

        assertEquals(ErrorCode.MODEL_AUTH_FAILED, error.getErrorCode());
        assertEquals(1, context.getAttempt());
    }

    /** 全部候选不可降级失败：抛最后一次错误（{@code shouldThrowLastErrorWhenAllFallback}）。 */
    @Test
    void shouldThrowLastErrorWhenAllFallback() {
        when(modelRouter.candidates(ModelCapability.CHAT, null))
                .thenReturn(List.of(model("a"), model("b")));
        CallContext context = context(candidate -> {
            throw new RateLimitException("429");
        });

        KnowledgeException error = assertThrows(KnowledgeException.class, () -> invoker.invoke(context));

        assertEquals(ErrorCode.MODEL_RATE_LIMITED, error.getErrorCode());
        assertEquals(2, context.getAttempt());
    }

    /** 无候选：按 40464 拒绝，不发起调用（{@code shouldRejectWhenNoCandidate}）。 */
    @Test
    void shouldRejectWhenNoCandidate() {
        when(modelRouter.candidates(ModelCapability.CHAT, null)).thenReturn(List.of());
        CallContext context = context(candidate -> "never");

        KnowledgeException error = assertThrows(KnowledgeException.class, () -> invoker.invoke(context));

        assertEquals(ErrorCode.MODEL_NOT_FOUND, error.getErrorCode());
        assertEquals(0, context.getAttempt());
    }
}
