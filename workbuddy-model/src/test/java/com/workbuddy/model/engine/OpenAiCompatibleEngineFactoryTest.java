package com.workbuddy.model.engine;

import com.workbuddy.model.config.ModelVendorProperties;
import com.workbuddy.model.listener.ModelCallTrace;
import com.workbuddy.model.listener.ModelCallTraceHolder;
import com.workbuddy.model.listener.UsageRecordingListener;
import com.workbuddy.model.support.FakeOpenAiServer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎工厂单测（假 OpenAI 兼容端点）：请求路径、Bearer 头、模型名与配置一致；超时与重试按配置生效；
 * 留空项的默认口径取类常量；HTTP 客户端装饰器按真实请求次数写进调用上下文。
 *
 * @author cxxl
 */
class OpenAiCompatibleEngineFactoryTest {

    private static final String CHAT_RESPONSE = "{\"id\":\"c1\",\"model\":\"qwen-max\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"pong\"},"
            + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}";

    private static final String EMBEDDING_RESPONSE = "{\"object\":\"list\",\"model\":\"text-embedding-v4\","
            + "\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2]}],"
            + "\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}";

    private ModelCallTraceHolder traceHolder;

    private OpenAiCompatibleEngineFactory factory;

    private FakeOpenAiServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeOpenAiServer();
        server.respondWith(200, CHAT_RESPONSE);
        traceHolder = new ModelCallTraceHolder();
        factory = new OpenAiCompatibleEngineFactory(new UsageRecordingListener(traceHolder), traceHolder);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void chatModelShouldSendPathModelAndBearerHeader() {
        ChatModel chatModel = factory.chatModel(vendor(10000, 0), "qwen-max");

        assertEquals("pong", chatModel.chat("ping"));

        assertEquals(FakeOpenAiServer.CHAT_PATH, server.lastPath());
        assertEquals("Bearer sk-test", server.lastAuthorization());
        assertTrue(server.lastBody().contains("\"qwen-max\""), server.lastBody());
    }

    @Test
    void embeddingModelShouldSendPathModelAndBearerHeader() {
        server.respondWith(200, EMBEDDING_RESPONSE);
        EmbeddingModel embeddingModel = factory.embeddingModel(vendor(10000, 0), "text-embedding-v4");

        List<Embedding> embeddings = embeddingModel.embedAll(List.of(TextSegment.from("甲"))).content();

        assertEquals(1, embeddings.size());
        assertEquals(2, embeddings.getFirst().dimension());
        assertEquals(FakeOpenAiServer.EMBEDDING_PATH, server.lastPath());
        assertEquals("Bearer sk-test", server.lastAuthorization());
        assertTrue(server.lastBody().contains("\"text-embedding-v4\""), server.lastBody());
        assertEquals(1, server.requestCount());
    }

    @Test
    void boundTraceShouldCountRealHttpAttempts() {
        ModelCallTrace trace = new ModelCallTrace();
        traceHolder.bind(trace);
        try {
            factory.chatModel(vendor(10000, 2), "qwen-max").chat("ping");
        } finally {
            traceHolder.clear();
        }

        assertEquals(1, server.requestCount());
        assertEquals(1, trace.attempts());
        assertEquals(0, trace.retryCount());
        assertNotNull(trace.lastAttemptLatencyMs());
    }

    @Test
    void maxRetriesZeroShouldNotRetry() {
        server.respondWith(500, "{\"error\":{\"message\":\"boom\"}}");
        ChatModel chatModel = factory.chatModel(vendor(10000, 0), "qwen-max");

        assertThrows(RuntimeException.class, () -> chatModel.chat("ping"));

        assertEquals(1, server.requestCount());
    }

    @Test
    void maxRetriesTwoShouldSendThreeAttempts() {
        server.respondWith(500, "{\"error\":{\"message\":\"boom\"}}");
        ChatModel chatModel = factory.chatModel(vendor(10000, 2), "qwen-max");

        assertThrows(RuntimeException.class, () -> chatModel.chat("ping"));

        assertEquals(3, server.requestCount());
    }

    @Test
    void configuredTimeoutShouldCutSlowResponse() {
        server.respondWith(200, CHAT_RESPONSE);
        server.delayMillis(1500);
        ChatModel chatModel = factory.chatModel(vendor(300, 0), "qwen-max");

        long start = System.currentTimeMillis();
        assertThrows(TimeoutException.class, () -> chatModel.chat("ping"));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 1500, "耗时 " + elapsed + " 毫秒，未按配置的 300 毫秒超时中断");
    }

    @Test
    void blankOptionsShouldFallBackToDocumentedDefaults() {
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", OpenAiCompatibleEngineFactory.DEFAULT_BASE_URL);
        assertEquals(60000, OpenAiCompatibleEngineFactory.DEFAULT_TIMEOUT_MS);

        ModelVendorProperties.Vendor vendor = new ModelVendorProperties.Vendor();
        vendor.setApiKey("sk-test");

        assertNotNull(factory.chatModel(vendor, "qwen-max"));
        assertNotNull(factory.embeddingModel(vendor, "text-embedding-v4"));
    }

    /** 指向假服务的厂商配置：密钥固定，超时与重试按用例给值（重试给 null 即留空） */
    private ModelVendorProperties.Vendor vendor(int timeoutMs, Integer maxRetries) {
        ModelVendorProperties.Vendor vendor = new ModelVendorProperties.Vendor();
        vendor.setBaseUrl(server.baseUrl());
        vendor.setApiKey("sk-test");
        vendor.setTimeoutMs(timeoutMs);
        vendor.setMaxRetries(maxRetries);
        return vendor;
    }
}
