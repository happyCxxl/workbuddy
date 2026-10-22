package com.workbuddy.model.provider;

import com.sun.net.httpserver.HttpServer;
import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.domain.model.chat.ChatMessage;
import com.workbuddy.common.domain.model.chat.ChatOptions;
import com.workbuddy.common.domain.model.chat.ChatRequest;
import com.workbuddy.common.domain.model.chat.ChatResult;
import com.workbuddy.common.domain.model.chat.OutputFormat;
import com.workbuddy.common.domain.model.chat.OutputSpec;
import com.workbuddy.common.domain.model.vision.VisionRequest;
import com.workbuddy.common.enums.model.FinishReason;
import com.workbuddy.model.config.ModelVendorProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI 兼容适配器单测：用本地假端点抓包，验证请求映射与响应回填。
 */
class OpenAiCompatibleAdapterTest {

    private static final String CHAT_RESPONSE = """
            {"id":"chat-1","object":"chat.completion","created":1,"model":"qwen-max",
             "choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
            """;

    private static final String EMBEDDING_RESPONSE = """
            {"object":"list","data":[
              {"object":"embedding","index":0,"embedding":[0.1,0.2,0.3]},
              {"object":"embedding","index":1,"embedding":[0.4,0.5,0.6]}],
             "model":"text-embedding-v4","usage":{"prompt_tokens":2,"total_tokens":2}}
            """;

    private HttpServer server;

    private final Map<String, String> requests = new LinkedHashMap<>();

    private OpenAiCompatibleAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions",
                jsonHandler("/v1/chat/completions", CHAT_RESPONSE));
        server.createContext("/v1/embeddings",
                jsonHandler("/v1/embeddings", EMBEDDING_RESPONSE));
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        ModelVendorProperties vendorProperties = new ModelVendorProperties();
        ModelVendorProperties.Vendor vendor = new ModelVendorProperties.Vendor();
        vendor.setBaseUrl(baseUrl);
        vendor.setApiKey("sk-test");
        vendor.setTimeoutMs(5000);
        vendorProperties.setVendors(Map.of("dashscope", vendor));
        adapter = new OpenAiCompatibleAdapter(new ModelInstanceRegistry(vendorProperties));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private com.sun.net.httpserver.HttpHandler jsonHandler(String path, String body) {
        return exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.put(path, request);
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        };
    }

    private ModelDescriptor model(String modelKey) {
        ModelDescriptor model = new ModelDescriptor();
        model.setVendorCode("dashscope");
        model.setModelKey(modelKey);
        return model;
    }

    /** chat：请求体带消息/采样参数，响应回填文本、模型、停止原因与用量（{@code shouldMapChat}）。 */
    @Test
    void shouldMapChat() {
        ChatOptions options = new ChatOptions();
        options.setTemperature(0.2);
        options.setMaxOutputTokens(64);
        ChatRequest request = new ChatRequest();
        request.setMessages(List.of(ChatMessage.system("you are helpful"), ChatMessage.user("hi")));
        request.setOptions(options);

        ChatResult result = adapter.chat(model("qwen-max"), request);

        assertEquals("hello", result.getContent());
        assertEquals("qwen-max", result.getModel());
        assertEquals(FinishReason.STOP, result.getFinishReason());
        assertNotNull(result.getTokenUsage());
        assertEquals(5, result.getTokenUsage().getInputTokens());
        assertEquals(3, result.getTokenUsage().getOutputTokens());
        assertEquals(8, result.getTokenUsage().getTotalTokens());
        String body = requests.get("/v1/chat/completions").replace(" ", "");
        assertTrue(body.contains("\"model\":\"qwen-max\""), body);
        assertTrue(body.contains("\"max_tokens\":64") || body.contains("\"max_completion_tokens\":64"), body);
    }

    /** JSON_OBJECT 输出：请求体带 response_format=json_object（{@code shouldMapJsonObjectOutput}）。 */
    @Test
    void shouldMapJsonObjectOutput() {
        OutputSpec output = new OutputSpec();
        output.setFormat(OutputFormat.JSON_OBJECT);
        ChatRequest request = new ChatRequest();
        request.setMessages(List.of(ChatMessage.user("hi")));
        request.setOutput(output);

        adapter.chat(model("qwen-max"), request);

        String body = requests.get("/v1/chat/completions").replace(" ", "");
        assertTrue(body.contains("\"response_format\""), body);
        assertTrue(body.contains("\"json_object\""), body);
    }

    /** JSON_SCHEMA 输出：请求体带 json_schema 与 schema 文本（{@code shouldMapJsonSchemaOutput}）。 */
    @Test
    void shouldMapJsonSchemaOutput() {
        OutputSpec output = new OutputSpec();
        output.setFormat(OutputFormat.JSON_SCHEMA);
        output.setSchema("{\"type\":\"object\"}");
        ChatRequest request = new ChatRequest();
        request.setMessages(List.of(ChatMessage.user("hi")));
        request.setOutput(output);

        adapter.chat(model("qwen-max"), request);

        String body = requests.get("/v1/chat/completions").replace(" ", "");
        assertTrue(body.contains("\"json_schema\""), body);
        assertTrue(body.contains("\"type\":\"object\""), body);
    }

    /** 视觉：字节按 MIME 组 data URL（{@code shouldMapVisionBytes}）。 */
    @Test
    void shouldMapVisionBytes() {
        VisionRequest request = new VisionRequest();
        request.setImageBytes(new byte[]{1, 2, 3});
        request.setMimeType("image/png");
        request.setPrompt("read");

        ChatResult result = adapter.vision(model("qwen-vl"), request);

        assertEquals("hello", result.getContent());
        String body = requests.get("/v1/chat/completions").replace(" ", "");
        assertTrue(body.contains("\"image_url\""), body);
        assertTrue(body.contains("data:image/png;base64,AQID"), body);
    }

    /** 视觉：引用按 URL 提交（{@code shouldMapVisionRef}）。 */
    @Test
    void shouldMapVisionRef() {
        VisionRequest request = new VisionRequest();
        request.setImageRef("https://example.com/a.png");

        adapter.vision(model("qwen-vl"), request);

        String body = requests.get("/v1/chat/completions").replace(" ", "");
        assertTrue(body.contains("https://example.com/a.png"), body);
    }

    /** 向量编码：文本按请求顺序提交，向量按同一顺序回填（{@code shouldMapEmbedding}）。 */
    @Test
    void shouldMapEmbedding() {
        EmbeddingRequest request = new EmbeddingRequest();
        request.setModel("text-embedding-v4");
        request.setTexts(List.of("a", "b"));
        request.setRequestId("rid-1");

        EmbeddingResult result = adapter.embed(model("text-embedding-v4"), request);

        assertEquals(2, result.getEmbeddings().size());
        assertEquals(3, result.getDimension());
        assertEquals(List.of(0.4f, 0.5f, 0.6f), result.getEmbeddings().get(1));
        assertEquals("text-embedding-v4", result.getModel());
        assertEquals("rid-1", result.getRequestId());
        String body = requests.get("/v1/embeddings").replace(" ", "");
        assertTrue(body.contains("\"model\":\"text-embedding-v4\""), body);
    }
}
