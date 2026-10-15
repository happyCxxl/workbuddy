package com.workbuddy.model.dashscope;

import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.model.adapter.OpenAiCompatibleAdapter;
import com.workbuddy.model.config.ModelVendorProperties;
import com.workbuddy.model.engine.ModelInstanceRegistry;
import com.workbuddy.model.engine.OpenAiCompatibleEngineFactory;
import com.workbuddy.model.invoke.ModelInvoker;
import com.workbuddy.model.listener.ModelCallTrace;
import com.workbuddy.model.listener.ModelCallTraceHolder;
import com.workbuddy.model.listener.UsageRecordingListener;
import com.workbuddy.model.support.FakeOpenAiServer;
import com.workbuddy.model.support.InMemoryModelCallLogPort;
import com.workbuddy.model.support.TestModelCatalog;
import com.workbuddy.model.support.TestModelInvoker;
import dev.langchain4j.exception.InternalServerException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量化提供者单测（本地假 OpenAI 兼容端点）：请求路径、Bearer 头、模型名与文本顺序，
 * 响应映射的向量条数与顺序、维度、模型回显、requestId，缺向量数据与密钥缺失的拒绝口径，
 * 「模型 → 厂商」解析（目录优先 + 单厂商兜底）与调用上下文观测四项。
 *
 * @author cxxl
 */
class DashScopeModelProviderTest {

    private static final String VENDOR = "dashscope";

    private static final String MODEL = "text-embedding-v4";

    private static final String EMBEDDING_RESPONSE = "{\"object\":\"list\",\"model\":\"text-embedding-v4\","
            + "\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2]},"
            + "{\"object\":\"embedding\",\"index\":1,\"embedding\":[0.3,0.4]}],"
            + "\"usage\":{\"prompt_tokens\":3,\"total_tokens\":3}}";

    private FakeOpenAiServer server;

    private ModelVendorProperties vendorProperties;

    private ModelCallTraceHolder traceHolder;

    private TestModelCatalog catalog;

    private DashScopeModelProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeOpenAiServer();
        server.respondWith(200, EMBEDDING_RESPONSE);
        traceHolder = new ModelCallTraceHolder();
        catalog = new TestModelCatalog().withModel(MODEL, VENDOR);
        vendorProperties = properties(Map.of(VENDOR, vendor("sk-test", 0)));
        provider = provider(vendorProperties);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void embedShouldSendPathModelAndBearerHeader() {
        EmbeddingRequest request = request();

        provider.embed(request);

        assertEquals(FakeOpenAiServer.EMBEDDING_PATH, server.lastPath());
        assertEquals("Bearer sk-test", server.lastAuthorization());
        assertTrue(server.lastBody().contains("\"" + MODEL + "\""), server.lastBody());
        assertTrue(server.lastBody().contains("第一句"), server.lastBody());
        assertTrue(server.lastBody().contains("第二句"), server.lastBody());
        assertEquals(1, server.requestCount());
    }

    @Test
    void embedShouldMapVectorsDimensionModelAndRequestId() {
        EmbeddingResult result = provider.embed(request());

        assertNotNull(result);
        assertEquals(2, result.getEmbeddings().size());
        assertEquals(2, result.getDimension());
        assertEquals(0.1f, result.getEmbeddings().get(0).get(0));
        assertEquals(0.2f, result.getEmbeddings().get(0).get(1));
        assertEquals(0.3f, result.getEmbeddings().get(1).get(0));
        assertEquals(0.4f, result.getEmbeddings().get(1).get(1));
        assertEquals(MODEL, result.getModel());
        assertEquals("req-1", result.getRequestId());
    }

    @Test
    void responseModelShouldWinOverRequestModel() {
        server.respondWith(200, "{\"object\":\"list\",\"model\":\"text-embedding-v4-2026\","
                + "\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2]}]}");

        EmbeddingResult result = provider.embed(request());

        assertEquals("text-embedding-v4-2026", result.getModel());
    }

    @Test
    void responseWithoutModelShouldFallBackToRequestModel() {
        server.respondWith(200, "{\"object\":\"list\","
                + "\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2]}]}");

        EmbeddingResult result = provider.embed(request());

        assertEquals(MODEL, result.getModel());
    }

    @Test
    void vendorShouldComeFromCatalogEvenWhenAnotherVendorIsConfigured() {
        vendorProperties.setVendors(Map.of("aliyun", vendor("sk-aliyun", 0)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider(vendorProperties).embed(request()));

        assertTrue(error.getMessage().contains("knowledge.model.vendors." + VENDOR + ".api-key"), error.getMessage());
        assertEquals(0, server.requestCount());
    }

    @Test
    void vendorShouldFallBackToTheOnlyConfiguredVendorWhenCatalogMissesModel() {
        vendorProperties.setVendors(Map.of("aliyun", vendor("sk-aliyun", 0)));
        EmbeddingRequest request = request();
        request.setModel("custom-embed");

        EmbeddingResult result = provider(vendorProperties).embed(request);

        assertEquals(2, result.getEmbeddings().size());
        assertEquals("Bearer sk-aliyun", server.lastAuthorization());
    }

    @Test
    void multipleVendorsShouldResolveVendorFromCatalog() {
        vendorProperties.setVendors(vendors(vendor("sk-dashscope", 0), "second", vendor("sk-second", 0)));

        EmbeddingResult result = provider(vendorProperties).embed(request());

        assertEquals(2, result.getEmbeddings().size());
        assertEquals("Bearer sk-dashscope", server.lastAuthorization());
        assertEquals(1, server.requestCount());
    }

    @Test
    void unknownModelWithMultipleVendorsShouldBeRejected() {
        vendorProperties.setVendors(vendors(vendor("sk-dashscope", 0), "second", vendor("sk-second", 0)));
        EmbeddingRequest request = request();
        request.setModel("no-such-model");

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> provider(vendorProperties).embed(request));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        assertTrue(error.getMessage().contains("no-such-model"), error.getMessage());
        assertEquals(0, server.requestCount());
    }

    @Test
    void emptyVendorsShouldBeRejectedWithVendorUnknown() {
        vendorProperties.setVendors(Map.of());

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> provider(vendorProperties).embed(request()));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        assertEquals(0, server.requestCount());
    }

    @Test
    void responseWithoutDataShouldMapToOutputInvalid() {
        server.respondWith(200, "{\"object\":\"list\",\"model\":\"text-embedding-v4\"}");

        KnowledgeException error = assertThrows(KnowledgeException.class, () -> provider.embed(request()));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
        assertEquals(40469, error.getCode());
        assertEquals(1, server.requestCount());
    }

    @Test
    void malformedResponseShouldThrow() {
        server.respondWith(200, "not-json");

        KnowledgeException error = assertThrows(KnowledgeException.class, () -> provider.embed(request()));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
        assertEquals(1, server.requestCount());
    }

    @Test
    void serverErrorShouldThrow() {
        server.respondWith(500, "{\"error\":{\"message\":\"boom\"}}");

        KnowledgeException error = assertThrows(KnowledgeException.class, () -> provider.embed(request()));

        assertEquals(ErrorCode.MODEL_SERVER_ERROR, error.getErrorCode());
        assertTrue(hasCause(error, InternalServerException.class), String.valueOf(error.getCause()));
        assertEquals(1, server.requestCount());
    }

    @Test
    void embedWithoutApiKeyShouldThrow() {
        vendorProperties.setVendors(Map.of(VENDOR, vendor("  ", 0)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider(vendorProperties).embed(request()));

        assertTrue(error.getMessage().contains("knowledge.model.vendors." + VENDOR + ".api-key"), error.getMessage());
        assertEquals(0, server.requestCount());
    }

    @Test
    void boundTraceShouldRecordAttemptLatencyAndUsage() {
        ModelCallTrace trace = new ModelCallTrace();

        traceHolder.bind(trace);
        try {
            provider.embed(request());
        } finally {
            traceHolder.clear();
        }

        assertEquals(1, server.requestCount());
        assertEquals(1, trace.attempts());
        assertEquals(0, trace.retryCount());
        assertNotNull(trace.lastAttemptLatencyMs());
        assertNotNull(trace.latencyMs());
        assertNotNull(trace.usage());
        assertEquals(3, trace.usage().getPromptTokens());
        assertNull(trace.usage().getCompletionTokens());
        assertEquals(3, trace.usage().getTotalTokens());
        assertNull(trace.errorCode());
        assertNull(trace.error());
    }

    @Test
    void responseWithoutUsageShouldLeaveUsageUnknown() {
        server.respondWith(200, "{\"object\":\"list\",\"model\":\"text-embedding-v4\","
                + "\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2]}]}");
        ModelCallTrace trace = new ModelCallTrace();

        traceHolder.bind(trace);
        try {
            provider.embed(request());
        } finally {
            traceHolder.clear();
        }

        assertEquals(1, trace.attempts());
        assertNull(trace.usage());
        assertNotNull(trace.latencyMs());
    }

    @Test
    void failedCallShouldRecordErrorAndLatencyOnTrace() {
        server.respondWith(500, "{\"error\":{\"message\":\"boom\"}}");
        ModelCallTrace trace = new ModelCallTrace();

        traceHolder.bind(trace);
        try {
            assertThrows(KnowledgeException.class, () -> provider.embed(request()));
        } finally {
            traceHolder.clear();
        }

        assertEquals(1, trace.attempts());
        assertNotNull(trace.error());
        assertNotNull(trace.latencyMs());
        assertEquals(1, server.requestCount());
    }

    @Test
    void embedWithoutBoundTraceShouldStillWork() {
        EmbeddingResult result = provider.embed(request());

        assertEquals(2, result.getEmbeddings().size());
        assertEquals(1, server.requestCount());
    }

    /** 带假服务端点的提供者：厂商配置、目录、实例注册表、调用器与调用上下文持有器按用例装配 */
    private DashScopeModelProvider provider(ModelVendorProperties properties) {
        OpenAiCompatibleEngineFactory engineFactory =
                new OpenAiCompatibleEngineFactory(new UsageRecordingListener(traceHolder), traceHolder);
        ModelInstanceRegistry registry = new ModelInstanceRegistry(properties, engineFactory, catalog);
        ModelInvoker invoker = TestModelInvoker.of(new OpenAiCompatibleAdapter(registry, traceHolder), traceHolder,
                new InMemoryModelCallLogPort(), catalog);
        return new DashScopeModelProvider(properties, registry, invoker, traceHolder);
    }

    /** 指向假服务的厂商配置：密钥与重试按用例给值，超时取 10000 毫秒 */
    private ModelVendorProperties.Vendor vendor(String apiKey, Integer maxRetries) {
        ModelVendorProperties.Vendor vendor = new ModelVendorProperties.Vendor();
        vendor.setBaseUrl(server.baseUrl());
        vendor.setApiKey(apiKey);
        vendor.setTimeoutMs(10000);
        vendor.setMaxRetries(maxRetries);
        return vendor;
    }

    /** 厂商配置容器：键序按入参顺序固定 */
    private ModelVendorProperties properties(Map<String, ModelVendorProperties.Vendor> vendors) {
        ModelVendorProperties properties = new ModelVendorProperties();
        properties.setVendors(vendors);
        return properties;
    }

    /** 两个厂商的配置容器：first 在前，otherCode 在后 */
    private Map<String, ModelVendorProperties.Vendor> vendors(ModelVendorProperties.Vendor first, String otherCode,
                                                             ModelVendorProperties.Vendor other) {
        Map<String, ModelVendorProperties.Vendor> vendors = new LinkedHashMap<>();
        vendors.put(VENDOR, first);
        vendors.put(otherCode, other);
        return vendors;
    }

    /** 向量入参：两条文本 + 模型名 + requestId */
    private EmbeddingRequest request() {
        EmbeddingRequest request = new EmbeddingRequest();
        request.setModel(MODEL);
        request.setTexts(List.of("第一句", "第二句"));
        request.setRequestId("req-1");
        return request;
    }

    /** 异常链上是否出现该类型（含自身） */
    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable node = error;
        while (node != null) {
            if (type.isInstance(node)) {
                return true;
            }
            node = node.getCause() == node ? null : node.getCause();
        }
        return false;
    }
}
