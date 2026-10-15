package com.workbuddy.model.listener;

import cn.hutool.json.JSONUtil;
import com.workbuddy.common.domain.model.ChatMessage;
import com.workbuddy.common.domain.model.ChatRequest;
import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.MessageRole;
import com.workbuddy.common.domain.model.OutputFormat;
import com.workbuddy.common.domain.model.OutputSpec;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.model.adapter.OpenAiCompatibleAdapter;
import com.workbuddy.model.config.ModelVendorProperties;
import com.workbuddy.model.engine.ModelInstanceRegistry;
import com.workbuddy.model.engine.OpenAiCompatibleEngineFactory;
import com.workbuddy.model.invoke.ModelInvoker;
import com.workbuddy.model.support.FakeOpenAiServer;
import com.workbuddy.model.support.InMemoryModelCallLogPort;
import com.workbuddy.model.support.TestModelCatalog;
import com.workbuddy.model.support.TestModelInvoker;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 用量监听单测（假 OpenAI 兼容端点）：一次调用的真实 HTTP 尝试次数与重试次数、用量三项、延迟、失败错误码，
 * 以及未带调用上下文的调用路径、向量路径与回调。
 *
 * @author cxxl
 */
class UsageRecordingListenerTest {

    private static final String VENDOR = "dashscope";

    private static final String MODEL = "qwen-max";

    private static final String EMBEDDING_MODEL = "text-embedding-v4";

    private static final String EMBEDDING_RESPONSE = "{\"object\":\"list\",\"model\":\"text-embedding-v4\","
            + "\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2]}],"
            + "\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}";

    private FakeOpenAiServer server;

    private ModelCallTraceHolder traceHolder;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeOpenAiServer();
        server.respondWith(200, chatResponse("pong"));
        traceHolder = new ModelCallTraceHolder();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void chatOnceShouldRecordOneHttpAttemptWithUsageAndLatency() {
        ModelCallTrace trace = new ModelCallTrace();

        GenerationResult result = invoker(0).chat(VENDOR, request(), trace);

        assertEquals("pong", result.getContent());
        assertEquals(1, server.requestCount());
        assertEquals(1, trace.attempts());
        assertEquals(0, trace.retryCount());
        assertNotNull(trace.lastAttemptLatencyMs());
        assertNotNull(trace.usage());
        assertEquals(3, trace.usage().getPromptTokens());
        assertEquals(2, trace.usage().getCompletionTokens());
        assertEquals(5, trace.usage().getTotalTokens());
        assertNotNull(trace.latencyMs());
        assertNull(trace.errorCode());
        assertNull(trace.error());
    }

    @Test
    void twoFailuresThenSuccessShouldCountThreeHttpAttempts() {
        server.failFirst(2, 500, errorBody("InternalError", "Internal server error"));
        ModelCallTrace trace = new ModelCallTrace();

        GenerationResult result = invoker(2).chat(VENDOR, request(), trace);

        assertEquals("pong", result.getContent());
        assertEquals(3, server.requestCount());
        assertEquals(3, trace.attempts());
        assertEquals(2, trace.retryCount());
        assertNotNull(trace.usage());
        assertNull(trace.errorCode());
    }

    @Test
    void exhaustedRetriesShouldCountEveryHttpAttempt() {
        server.respondWith(500, errorBody("InternalError", "Internal server error"));
        ModelCallTrace trace = new ModelCallTrace();

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> invoker(2).chat(VENDOR, request(), trace));

        assertEquals(ErrorCode.MODEL_SERVER_ERROR, error.getErrorCode());
        assertEquals(3, server.requestCount());
        assertEquals(3, trace.attempts());
        assertEquals(2, trace.retryCount());
        assertNotNull(trace.error());
        assertEquals(ErrorCode.MODEL_SERVER_ERROR, trace.errorCode());
    }

    @Test
    void singleFailedCallShouldRecordErrorCodeAndLatency() {
        server.respondWith(500, errorBody("InternalError", "Internal server error"));
        ModelCallTrace trace = new ModelCallTrace();

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> invoker(0).chat(VENDOR, request(), trace));

        assertEquals(ErrorCode.MODEL_SERVER_ERROR, error.getErrorCode());
        assertEquals(ErrorCode.MODEL_SERVER_ERROR, trace.errorCode());
        assertNotNull(trace.error());
        assertNotNull(trace.latencyMs());
        assertEquals(1, trace.attempts());
        assertEquals(0, trace.retryCount());
        assertEquals(1, server.requestCount());
    }

    @Test
    void responseWithoutUsageShouldLeaveUsageUnknown() {
        server.respondWith(200, responseWithoutUsage());
        ModelCallTrace trace = new ModelCallTrace();

        invoker(0).chat(VENDOR, request(), trace);

        assertEquals(1, trace.attempts());
        assertNull(trace.usage());
    }

    @Test
    void responseWithPartialUsageShouldKeepMissingFieldsNull() {
        server.respondWith(200, responseWithPartialUsage());
        ModelCallTrace trace = new ModelCallTrace();

        invoker(0).chat(VENDOR, request(), trace);

        assertNotNull(trace.usage());
        assertEquals(7, trace.usage().getPromptTokens());
        assertNull(trace.usage().getCompletionTokens());
        assertNull(trace.usage().getTotalTokens());
    }

    @Test
    void attemptsShouldAccumulateAcrossCallsOnSameTrace() {
        ModelCallTrace trace = new ModelCallTrace();
        ModelInvoker invoker = invoker(0);

        invoker.chat(VENDOR, request(), trace);
        invoker.chat(VENDOR, request(), trace);

        assertEquals(2, trace.attempts());
        assertEquals(2, server.requestCount());
    }

    @Test
    void chatWithoutTraceShouldStillWork() {
        GenerationResult result = invoker(0).chat(VENDOR, request());

        assertEquals("pong", result.getContent());
        assertNotNull(result.getUsage());
        assertEquals(1, server.requestCount());
    }

    @Test
    void embeddingCallShouldPassThroughDecoratedClient() {
        server.respondWith(200, EMBEDDING_RESPONSE);

        List<Embedding> embeddings = embeddingModel(0).embedAll(List.of(TextSegment.from("甲"))).content();

        assertEquals(1, embeddings.size());
        assertEquals(2, embeddings.getFirst().dimension());
        assertEquals(1, server.requestCount());
    }

    @Test
    void embeddingCallWithBoundTraceShouldCountHttpAttempts() {
        server.respondWith(200, EMBEDDING_RESPONSE);
        ModelCallTrace trace = new ModelCallTrace();
        EmbeddingModel embeddingModel = embeddingModel(0);

        traceHolder.bind(trace);
        try {
            embeddingModel.embedAll(List.of(TextSegment.from("甲")));
        } finally {
            traceHolder.clear();
        }

        assertEquals(1, trace.attempts());
        assertEquals(0, trace.retryCount());
        assertNotNull(trace.lastAttemptLatencyMs());
        assertNotNull(trace.latencyMs());
        assertNotNull(trace.usage());
        assertEquals(1, trace.usage().getPromptTokens());
        assertNull(trace.usage().getCompletionTokens());
        assertEquals(1, trace.usage().getTotalTokens());
        assertEquals(1, server.requestCount());
    }

    @Test
    void listenerShouldSkipContextWithoutTrace() {
        UsageRecordingListener listener = new UsageRecordingListener(traceHolder);
        dev.langchain4j.model.chat.request.ChatRequest lc4jRequest =
                dev.langchain4j.model.chat.request.ChatRequest.builder().messages(UserMessage.from("ping")).build();
        Map<Object, Object> attributes = Map.of();

        assertDoesNotThrow(() -> {
            listener.onRequest(new ChatModelRequestContext(lc4jRequest, ModelProvider.OPEN_AI, attributes));
            listener.onResponse(new ChatModelResponseContext(lc4jResponse("pong"), lc4jRequest, ModelProvider.OPEN_AI,
                    attributes));
            listener.onError(new ChatModelErrorContext(new RuntimeException("boom"), lc4jRequest, ModelProvider.OPEN_AI,
                    attributes));
        });
    }

    /** 带假服务的调用器：监听器与 HTTP 客户端装饰器挂在模型实例上，调用上下文持有器由调用器与适配器共用同一个实例 */
    private ModelInvoker invoker(Integer maxRetries) {
        OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(registry(maxRetries), traceHolder);
        return TestModelInvoker.of(adapter, traceHolder, new InMemoryModelCallLogPort(), new TestModelCatalog());
    }

    /** 带假服务的向量模型实例：与调用器共用同一个调用上下文持有器 */
    private EmbeddingModel embeddingModel(Integer maxRetries) {
        return registry(maxRetries).embeddingModel(VENDOR, EMBEDDING_MODEL);
    }

    /** 指向假服务的实例注册表：厂商配置取本用例的假服务地址 */
    private ModelInstanceRegistry registry(Integer maxRetries) {
        ModelVendorProperties properties = new ModelVendorProperties();
        Map<String, ModelVendorProperties.Vendor> vendors = new LinkedHashMap<>();
        ModelVendorProperties.Vendor vendor = new ModelVendorProperties.Vendor();
        vendor.setBaseUrl(server.baseUrl());
        vendor.setApiKey("sk-listener");
        vendor.setTimeoutMs(10000);
        vendor.setMaxRetries(maxRetries);
        vendors.put(VENDOR, vendor);
        properties.setVendors(vendors);
        OpenAiCompatibleEngineFactory engineFactory =
                new OpenAiCompatibleEngineFactory(new UsageRecordingListener(traceHolder), traceHolder);
        return new ModelInstanceRegistry(properties, engineFactory, new TestModelCatalog());
    }

    /** 对话入参：单条用户消息 + TEXT 输出 */
    private ChatRequest request() {
        ChatRequest request = new ChatRequest();
        request.setModelKey(MODEL);
        List<ChatMessage> messages = new ArrayList<>();
        ChatMessage message = new ChatMessage();
        message.setRole(MessageRole.USER);
        message.setContent("ping");
        messages.add(message);
        request.setMessages(messages);
        OutputSpec output = new OutputSpec();
        output.setFormat(OutputFormat.TEXT);
        request.setOutput(output);
        request.setTimeoutMs(10000);
        return request;
    }

    /** 正常应答：单条助手消息 + 用量 */
    private String chatResponse(String content) {
        return "{\"id\":\"c1\",\"model\":\"" + MODEL + "\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":" + JSONUtil.quote(content) + "},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}";
    }

    /** 应答缺 usage 字段 */
    private String responseWithoutUsage() {
        return "{\"id\":\"c2\",\"model\":\"" + MODEL + "\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"pong\"},\"finish_reason\":\"stop\"}]}";
    }

    /** 应答带 usage 但只回提示词一项 */
    private String responseWithPartialUsage() {
        return "{\"id\":\"c3\",\"model\":\"" + MODEL + "\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"pong\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":7}}";
    }

    /** 错误应答体（OpenAI 兼容形态） */
    private String errorBody(String code, String message) {
        return "{\"error\":{\"code\":" + JSONUtil.quote(code) + ",\"message\":" + JSONUtil.quote(message)
                + ",\"type\":\"invalid_request_error\"}}";
    }

    /** LC4j 响应对象（回调单测用） */
    private ChatResponse lc4jResponse(String content) {
        return ChatResponse.builder().aiMessage(AiMessage.from(content)).tokenUsage(new TokenUsage(3, 2, 5)).build();
    }
}
