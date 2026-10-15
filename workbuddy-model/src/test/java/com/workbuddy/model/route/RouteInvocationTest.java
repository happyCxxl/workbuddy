package com.workbuddy.model.route;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.ChatMessage;
import com.workbuddy.common.domain.model.ChatRequest;
import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.MessageRole;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.domain.model.calllog.ModelCallLogEntry;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.enums.model.CallStatus;
import com.workbuddy.common.enums.model.RouteReason;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.model.invoke.ModelInvoker;
import com.workbuddy.model.support.RecordingAdapter;
import com.workbuddy.model.support.RouteFixture;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按能力选型的调用链单测：候选顺序、换模型降级链、明细里的 route_reason 与 fallback_from，
 * 以及容量优先的选型结论落到真实调用上。
 *
 * <p>驱动方式：假目录给候选，假适配器按模型名决定成功或失败，路由状态由用例直接构造读数，
 * 明细端口用内存实现（断言 kb_model_call_log 那一行的字段）。
 *
 * @author cxxl
 */
class RouteInvocationTest {

    private static final String VENDOR = "dashscope";

    /** 名字靠前的候选：并列时按模型名定序，它是首选 */
    private static final String PRIMARY = "qwen-max";

    /** 名字靠后的候选：首选失守时由它接住 */
    private static final String SECONDARY = "qwen-plus";

    @Test
    void idleCandidateShouldBeCalledWhenOtherIsBusy() {
        RouteFixture fixture = twoCandidates();
        fixture.catalog().findByVendorAndModelKey(VENDOR, PRIMARY).setRpmLimit(100);
        fixture.catalog().findByVendorAndModelKey(VENDOR, SECONDARY).setRpmLimit(100);
        occupy(fixture.stateStore(), PRIMARY, 80, 100L);
        occupy(fixture.stateStore(), SECONDARY, 8, 900L);
        RecordingAdapter adapter = new RecordingAdapter();
        ModelInvoker invoker = fixture.invoker(adapter);

        GenerationResult result = invoker.chatByCapability(ModelCapability.CHAT, request());

        assertEquals(SECONDARY, result.getModel());
        assertEquals(List.of(SECONDARY), adapter.called());
        assertEquals(1, fixture.callLog().entries().size());
        ModelCallLogEntry entry = fixture.callLog().lastEntry();
        assertEquals(RouteReason.CAPACITY_HIGH, entry.getRouteReason());
        assertNull(entry.getFallbackFrom());
        assertEquals(CallStatus.SUCCESS, entry.getStatus());
        assertEquals(SECONDARY, entry.getModel());
    }

    @Test
    void firstCandidateFailureShouldFallBackToNextWithFallbackFrom() {
        RouteFixture fixture = twoCandidates();
        RecordingAdapter adapter = new RecordingAdapter().failing(PRIMARY, ErrorCode.MODEL_SERVER_ERROR);
        ModelInvoker invoker = fixture.invoker(adapter);

        GenerationResult result = invoker.chatByCapability(ModelCapability.CHAT, request());

        assertEquals(SECONDARY, result.getModel());
        assertEquals(List.of(PRIMARY, SECONDARY), adapter.called());
        List<ModelCallLogEntry> entries = fixture.callLog().entries();
        assertEquals(2, entries.size());
        // 首次尝试：失败一行，本次没有降级来源，原因按健康不佳记
        ModelCallLogEntry first = entries.getFirst();
        assertEquals(PRIMARY, first.getModel());
        assertEquals(CallStatus.FAILED, first.getStatus());
        assertEquals("MODEL_SERVER_ERROR", first.getErrorCode());
        assertEquals(RouteReason.UNHEALTHY, first.getRouteReason());
        assertNull(first.getFallbackFrom());
        // 换模后落第二行：模型是次选，原因是降级，来源记原模型
        ModelCallLogEntry second = entries.getLast();
        assertEquals(SECONDARY, second.getModel());
        assertEquals(CallStatus.SUCCESS, second.getStatus());
        assertEquals(RouteReason.FALLBACK_FROM, second.getRouteReason());
        assertEquals(PRIMARY, second.getFallbackFrom());
        assertEquals(15L, second.getTotalTokens());
        assertEquals(new BigDecimal("0.000060"), second.getCost());
        // 可降级的失败计入熔断器的失败条数（一次不足以推到打开）
        assertEquals(1, fixture.breakers().breaker(VENDOR, PRIMARY).getMetrics().getNumberOfFailedCalls());
        assertEquals(CircuitBreaker.State.CLOSED, fixture.breakers().state(VENDOR, PRIMARY));
    }

    @Test
    void nonFallbackErrorShouldNotTryNextCandidate() {
        RouteFixture fixture = twoCandidates();
        RecordingAdapter adapter = new RecordingAdapter().failing(PRIMARY, ErrorCode.MODEL_BAD_REQUEST);
        ModelInvoker invoker = fixture.invoker(adapter);

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> invoker.chatByCapability(ModelCapability.CHAT, request()));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
        assertEquals(List.of(PRIMARY), adapter.called());
        assertEquals(1, fixture.callLog().entries().size());
        assertNull(fixture.callLog().lastEntry().getFallbackFrom());
        assertEquals(CallStatus.FAILED, fixture.callLog().lastEntry().getStatus());
        assertEquals(RouteReason.OK, fixture.callLog().lastEntry().getRouteReason());
        // 参数错不改变模型健康：不计入熔断器失败条数，按成功条数记
        assertEquals(0, fixture.breakers().breaker(VENDOR, PRIMARY).getMetrics().getNumberOfFailedCalls());
        assertEquals(1, fixture.breakers().breaker(VENDOR, PRIMARY).getMetrics().getNumberOfSuccessfulCalls());
    }

    @Test
    void allCandidatesFailingShouldThrowLastError() {
        RouteFixture fixture = twoCandidates();
        RecordingAdapter adapter = new RecordingAdapter().failing(PRIMARY, ErrorCode.MODEL_SERVER_ERROR)
                .failing(SECONDARY, ErrorCode.MODEL_TIMEOUT);
        ModelInvoker invoker = fixture.invoker(adapter);

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> invoker.chatByCapability(ModelCapability.CHAT, request()));

        assertEquals(ErrorCode.MODEL_TIMEOUT, error.getErrorCode());
        assertEquals(List.of(PRIMARY, SECONDARY), adapter.called());
        List<ModelCallLogEntry> entries = fixture.callLog().entries();
        assertEquals(2, entries.size());
        assertEquals("MODEL_SERVER_ERROR", entries.getFirst().getErrorCode());
        assertEquals("MODEL_TIMEOUT", entries.getLast().getErrorCode());
        assertEquals(RouteReason.FALLBACK_FROM, entries.getLast().getRouteReason());
        assertEquals(PRIMARY, entries.getLast().getFallbackFrom());
    }

    @Test
    void capabilityWithoutCandidatesShouldRejectWithoutCallLogRow() {
        RouteFixture fixture = new RouteFixture();
        fixture.withVendor(VENDOR);
        RecordingAdapter adapter = new RecordingAdapter();
        ModelInvoker invoker = fixture.invoker(adapter);

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> invoker.chatByCapability(ModelCapability.CHAT, request()));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        assertTrue(adapter.called().isEmpty());
        // 选型阶段就拒绝：请求没有发出，明细不落行
        assertEquals(0, fixture.callLog().entries().size());
    }

    @Test
    void nullRequestShouldBeRejectedWithoutCallLogRow() {
        RouteFixture fixture = twoCandidates();
        ModelInvoker invoker = fixture.invoker(new RecordingAdapter());

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> invoker.chatByCapability(ModelCapability.CHAT, null));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
        assertEquals(0, fixture.callLog().entries().size());
    }

    @Test
    void embedByCapabilityShouldCarryFallbackReason() {
        RouteFixture fixture = twoCandidates();
        RecordingAdapter adapter = new RecordingAdapter().failing(PRIMARY, ErrorCode.MODEL_SERVER_ERROR);
        ModelInvoker invoker = fixture.invoker(adapter);

        EmbeddingResult result = invoker.embedByCapability(ModelCapability.CHAT, embedRequest());

        assertEquals(2, result.getEmbeddings().size());
        assertEquals(2, result.getDimension());
        assertEquals(SECONDARY, result.getModel());
        assertEquals(List.of(PRIMARY, SECONDARY), adapter.called());
        ModelCallLogEntry entry = fixture.callLog().lastEntry();
        assertEquals(ModelCapability.EMBEDDING, entry.getCapability());
        assertEquals(RouteReason.FALLBACK_FROM, entry.getRouteReason());
        assertEquals(PRIMARY, entry.getFallbackFrom());
        assertEquals(1, entry.getUsageUnknown());
    }

    @Test
    void legacyEntryShouldNotWriteRouteColumns() {
        RouteFixture fixture = new RouteFixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, PRIMARY, 100);
        ModelInvoker invoker = fixture.invoker(new RecordingAdapter());

        GenerationResult result = invoker.chat(VENDOR, request());

        assertEquals(PRIMARY, result.getModel());
        assertNull(fixture.callLog().lastEntry().getRouteReason());
        assertNull(fixture.callLog().lastEntry().getFallbackFrom());
    }

    @Test
    void visionShouldPickCandidateByCapabilityAndWriteItsCapabilityColumn() {
        RouteFixture fixture = twoVisionCandidates();
        RecordingAdapter adapter = new RecordingAdapter();
        ModelInvoker invoker = fixture.invoker(adapter);

        GenerationResult result = invoker.visionByCapability(ModelCapability.OCR, visionRequest());

        assertEquals(PRIMARY, result.getModel());
        assertEquals(List.of(PRIMARY), adapter.called());
        ModelCallLogEntry entry = fixture.callLog().lastEntry();
        assertEquals(ModelCapability.OCR, entry.getCapability());
        assertEquals(PRIMARY, entry.getModel());
        assertEquals(VENDOR, entry.getVendor());
        assertEquals(CallStatus.SUCCESS, entry.getStatus());
        assertEquals(1, entry.getUsageUnknown(), "OCR 的计费单位未定，用量按未知记");
    }

    @Test
    void visionCapabilityWithoutCandidateShouldRejectWithVendorUnknown() {
        RouteFixture fixture = new RouteFixture();
        fixture.withVendor(VENDOR);
        fixture.withVisionModel(VENDOR, PRIMARY, ModelCapability.LAYOUT);
        ModelInvoker invoker = fixture.invoker(new RecordingAdapter());

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> invoker.visionByCapability(ModelCapability.OCR, visionRequest()));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        assertTrue(fixture.callLog().entries().isEmpty(), "无候选的失败不落明细");
    }

    @Test
    void visionFirstCandidateFailureShouldFallBackToNextCandidate() {
        RouteFixture fixture = twoVisionCandidates();
        RecordingAdapter adapter = new RecordingAdapter().failing(PRIMARY, ErrorCode.MODEL_SERVER_ERROR);
        ModelInvoker invoker = fixture.invoker(adapter);

        GenerationResult result = invoker.visionByCapability(ModelCapability.OCR, visionRequest());

        assertEquals(SECONDARY, result.getModel());
        assertEquals(List.of(PRIMARY, SECONDARY), adapter.called());
        List<ModelCallLogEntry> entries = fixture.callLog().entries();
        assertEquals(2, entries.size());
        assertEquals(ModelCapability.OCR, entries.getFirst().getCapability());
        assertEquals(CallStatus.FAILED, entries.getFirst().getStatus());
        assertEquals(ModelCapability.OCR, entries.getLast().getCapability());
        assertEquals(RouteReason.FALLBACK_FROM, entries.getLast().getRouteReason());
        assertEquals(PRIMARY, entries.getLast().getFallbackFrom());
    }

    @Test
    void visionNullRequestShouldBeRejectedBeforeRoutePlan() {
        RouteFixture fixture = twoVisionCandidates();
        ModelInvoker invoker = fixture.invoker(new RecordingAdapter());

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> invoker.visionByCapability(ModelCapability.OCR, null));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
        assertTrue(fixture.callLog().entries().isEmpty());
    }

    /** 两个候选：都声明文字识别能力 */
    private RouteFixture twoVisionCandidates() {
        RouteFixture fixture = new RouteFixture();
        fixture.withVendor(VENDOR);
        fixture.withVisionModel(VENDOR, PRIMARY, ModelCapability.OCR)
                .withVisionModel(VENDOR, SECONDARY, ModelCapability.OCR);
        return fixture;
    }

    /** 两个候选：限额都留空，占用读数只由用例构造 */
    private RouteFixture twoCandidates() {
        RouteFixture fixture = new RouteFixture();
        fixture.withVendor(VENDOR);
        fixture.withChatModel(VENDOR, PRIMARY, null).withChatModel(VENDOR, SECONDARY, null);
        return fixture;
    }

    /** 造占用读数 */
    private void occupy(RouteStateStore store, String modelKey, int count, long latencyMs) {
        for (int i = 0; i < count; i++) {
            store.record(VENDOR, modelKey, true, latencyMs, null, 100L, true);
        }
    }

    /** 对话入参：单条用户消息 + 纯文本输出 */
    private ChatRequest request() {
        ChatRequest request = new ChatRequest();
        request.setModelKey(PRIMARY);
        List<ChatMessage> messages = new ArrayList<>();
        ChatMessage message = new ChatMessage();
        message.setRole(MessageRole.USER);
        message.setContent("ping");
        messages.add(message);
        request.setMessages(messages);
        request.setTimeoutMs(10000);
        return request;
    }

    /** 向量入参：两条短文本 + requestId */
    private EmbeddingRequest embedRequest() {
        EmbeddingRequest request = new EmbeddingRequest();
        request.setModel(PRIMARY);
        request.setTexts(List.of("第一句", "第二句"));
        request.setRequestId("req-route");
        return request;
    }

    /** 视觉入参：图像字节 + 提示词（模型名由选型结果回填） */
    private VisionRequest visionRequest() {
        VisionRequest request = new VisionRequest();
        request.setImageBytes(new byte[]{1, 2, 3, 4});
        request.setPrompt("按图给出逐行文本");
        return request;
    }
}
