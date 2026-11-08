package com.workbuddy.infra.modelgateway;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 模型网关客户端单测：本地 HttpServer 充当网关，守护信封组装、鉴权头、错误归一与响应直译（JAVA-TEST-011）。
 */
class ModelGatewayClientTest {

    private final Gson gson = new Gson();
    private final AtomicReference<Map<?, ?>> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedToken = new AtomicReference<>();
    private final AtomicReference<String> nextResponse = new AtomicReference<>();

    private HttpServer server;
    private ModelGatewayProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        capturedBody.set(null);
        capturedToken.set(null);
        nextResponse.set(null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1", this::handle);
        server.start();
        properties = new ModelGatewayProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setInternalToken("tok");
        properties.setTimeoutMs(5000);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** 网关替身：记下请求体与鉴权头，返回预设响应。 */
    private void handle(HttpExchange exchange) throws IOException {
        capturedToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        capturedBody.set(gson.fromJson(body, Map.class));
        byte[] payload = nextResponse.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    /** 构造最小向量化请求。 */
    private EmbeddingRequest embedRequest(String requestId) {
        EmbeddingRequest request = new EmbeddingRequest();
        request.setModel("m1");
        request.setTexts(List.of("你好"));
        request.setDimension(2);
        request.setTimeoutMs(3000);
        request.setRequestId(requestId);
        return request;
    }

    /**
     * embed 成功路径：信封字段、鉴权头与 payload 原样发出，vectors 按序直译返回。
     * <p>守护 {@link ModelGatewayClient#embed} 的请求信封与响应直译；信封字段或向量顺序变化时失败。
     */
    @Test
    void embedSendsEnvelopeAndParsesVectors() {
        nextResponse.set("{\"code\":0,\"data\":{\"vectors\":[[0.5,0.25]],\"meta\":{\"model_key\":\"m1\"}}}");

        EmbedResponse response = new ModelGatewayClient(properties).embed(embedRequest("rid-1"));

        assertEquals("tok", capturedToken.get());
        assertEquals("rid-1", capturedBody.get().get("request_id"));
        assertEquals("knowledge.embed", capturedBody.get().get("source"));
        assertEquals("EMBEDDING", capturedBody.get().get("capability"));
        Map<?, ?> payload = (Map<?, ?>) capturedBody.get().get("payload");
        assertEquals(List.of("你好"), payload.get("input"));
        assertEquals(2.0, payload.get("dimensions"));
        assertEquals("document", payload.get("input_type"));
        assertEquals("m1", payload.get("model_key"));
        assertEquals(List.of(List.of(0.5f, 0.25f)), response.vectors());
        assertEquals("m1", response.meta().get("model_key"));
    }

    /**
     * 网关 code≠0：按 MODEL_SERVER_ERROR 归一，message 透传。
     * <p>守护错误码 {@code MODEL_SERVER_ERROR}；归一码或 message 透传策略变化时失败。
     */
    @Test
    void embedFailureCodeNonZeroMapsToServerError() {
        nextResponse.set("{\"code\":500,\"msg\":\"候选模型全部失败\"}");

        WorkbuddyException error = assertThrows(WorkbuddyException.class,
                () -> new ModelGatewayClient(properties).embed(embedRequest("rid-2")));

        assertEquals(ErrorCode.MODEL_SERVER_ERROR.getCode(), error.getCode());
        assertEquals("候选模型全部失败", error.getMessage());
    }

    /**
     * 网关 code=0 但缺 data：按 MODEL_SERVER_ERROR 归一。
     * <p>守护错误码 {@code MODEL_SERVER_ERROR}；缺 data 不再拒绝时失败。
     */
    @Test
    void embedMissingDataMapsToServerError() {
        nextResponse.set("{\"code\":0}");

        WorkbuddyException error = assertThrows(WorkbuddyException.class,
                () -> new ModelGatewayClient(properties).embed(embedRequest("rid-3")));

        assertEquals(ErrorCode.MODEL_SERVER_ERROR.getCode(), error.getCode());
    }

    /**
     * ocrLines：图片字节以 base64 进 file_base64，text/fields/pages/meta 直译返回。
     * <p>守护 {@link ModelGatewayClient#ocrLines} 的入参映射与响应直译；base64、schema 或字段直译变化时失败。
     */
    @Test
    void ocrLinesSendsBase64AndParsesFields() {
        nextResponse.set("{\"code\":0,\"data\":{\"text\":\"t\",\"pages\":1,"
                + "\"fields\":{\"text_lines\":[{\"text\":\"行\"}]},\"meta\":{\"model_key\":\"ocr\"}}}");
        byte[] image = {1, 2, 3};
        Map<String, Object> params = Map.of("k", "v");

        OcrResponse response = new ModelGatewayClient(properties)
                .ocrLines("rid-4", "parse.ocr", image, "image/png", "ocr_lines", params, "ocr_lines", 1);

        assertEquals("OCR", capturedBody.get().get("capability"));
        assertEquals("parse.ocr", capturedBody.get().get("source"));
        Map<?, ?> payload = (Map<?, ?>) capturedBody.get().get("payload");
        assertEquals(Base64.getEncoder().encodeToString(image), payload.get("file_base64"));
        assertEquals("image/png", payload.get("mime_type"));
        assertEquals("ocr_lines", payload.get("prompt_key"));
        assertEquals(params, payload.get("prompt_params"));
        assertEquals("ocr_lines", payload.get("schema_key"));
        assertEquals(1.0, payload.get("pdf_page_limit"));
        assertEquals("t", response.text());
        assertEquals(1, response.pages());
        assertNotNull(response.fields());
        assertEquals(1, ((List<?>) response.fields().get("text_lines")).size());
        assertEquals("ocr", response.meta().get("model_key"));
    }

    /**
     * layoutAreas：与文字识别同端点，capability 记为 LAYOUT（网关按独立模型池路由）。
     * <p>守护 {@link ModelGatewayClient#layoutAreas} 的 LAYOUT 能力路由；并入 OCR 模型池时失败。
     */
    @Test
    void layoutAreasSendsLayoutCapability() {
        nextResponse.set("{\"code\":0,\"data\":{\"text\":\"t\",\"pages\":1,"
                + "\"fields\":{\"layout_areas\":[{\"type\":\"text\",\"bbox\":[1,2,3,4]}]},\"meta\":{}}}");

        OcrResponse response = new ModelGatewayClient(properties)
                .layoutAreas("rid-5", "parse.layout", new byte[] {9}, "image/png",
                        "layout_analyze", Map.of(), "layout");

        assertEquals("LAYOUT", capturedBody.get().get("capability"));
        assertEquals("parse.layout", capturedBody.get().get("source"));
        Map<?, ?> payload = (Map<?, ?>) capturedBody.get().get("payload");
        assertEquals("layout_analyze", payload.get("prompt_key"));
        assertEquals("layout", payload.get("schema_key"));
        assertEquals(1.0, payload.get("pdf_page_limit"));
        assertNotNull(response.fields());
        assertEquals(1, ((List<?>) response.fields().get("layout_areas")).size());
    }

    /**
     * 网关不可达（连接拒绝）：按 MODEL_UNREACHABLE 归一。
     * <p>守护错误码 {@code MODEL_UNREACHABLE}；连接拒绝不再归一或码值变化时失败。
     */
    @Test
    void unreachableGatewayMapsToUnreachable() throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        ModelGatewayProperties offline = new ModelGatewayProperties();
        offline.setBaseUrl("http://127.0.0.1:" + freePort);
        offline.setInternalToken("tok");
        offline.setTimeoutMs(2000);

        WorkbuddyException error = assertThrows(WorkbuddyException.class,
                () -> new ModelGatewayClient(offline).embed(embedRequest("rid-5")));

        assertEquals(ErrorCode.MODEL_UNREACHABLE.getCode(), error.getCode());
    }
}
