package com.workbuddy.model.invoke;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.workbuddy.common.domain.embed.EmbeddingRequest;
import com.workbuddy.common.domain.embed.EmbeddingResult;
import com.workbuddy.common.domain.model.AuthType;
import com.workbuddy.common.domain.model.ChatMessage;
import com.workbuddy.common.domain.model.ChatRequest;
import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.MessageRole;
import com.workbuddy.common.domain.model.OutputFormat;
import com.workbuddy.common.domain.model.OutputSpec;
import com.workbuddy.common.domain.model.VisionRequest;
import com.workbuddy.common.domain.model.calllog.ModelCallLogEntry;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.enums.model.CallStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.model.adapter.ModelAdapter;
import com.workbuddy.model.adapter.OpenAiCompatibleAdapter;
import com.workbuddy.model.catalog.ModelCatalogPort;
import com.workbuddy.model.config.ModelVendorProperties;
import com.workbuddy.model.engine.ModelInstanceRegistry;
import com.workbuddy.model.engine.OpenAiCompatibleEngineFactory;
import com.workbuddy.model.listener.ModelCallTrace;
import com.workbuddy.model.listener.ModelCallTraceHolder;
import com.workbuddy.model.listener.UsageRecordingListener;
import com.workbuddy.model.support.FakeOpenAiServer;
import com.workbuddy.model.support.InMemoryModelCallLogPort;
import com.workbuddy.model.support.TestModelInvoker;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 调用器单测（假 OpenAI 兼容端点）：一次调用的请求次数、异常到错误码的映射与可降级结论、
 * 结构校验失败的口径与失败前的调用前拒绝、调用结束后落调用明细的口径（成功、失败与未发出请求三态）。
 *
 * @author cxxl
 */
class ModelInvokerTest {

    private static final String VENDOR = "dashscope";

    private static final String MODEL = "qwen-max";

    /** 严格校验用 schema：name 必填且为字符串，age 为整数 */
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
            + "\"age\":{\"type\":\"integer\"}},\"required\":[\"name\"],\"additionalProperties\":false}";

    /** 视觉侧严格校验用 schema：lines 必填且为对象数组 */
    private static final String VISION_SCHEMA = "{\"type\":\"object\",\"properties\":{\"lines\":{\"type\":\"array\","
            + "\"items\":{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},"
            + "\"required\":[\"text\"],\"additionalProperties\":false}}},"
            + "\"required\":[\"lines\"],\"additionalProperties\":false}";

    /** 向量应答：两条向量 + 模型回显 + 用量 */
    private static final String EMBEDDING_RESPONSE = "{\"object\":\"list\",\"model\":\"" + MODEL + "\","
            + "\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.1,0.2]},"
            + "{\"object\":\"embedding\",\"index\":1,\"embedding\":[0.3,0.4]}],"
            + "\"usage\":{\"prompt_tokens\":3,\"total_tokens\":3}}";

    private FakeOpenAiServer server;

    private ModelInvoker invoker;

    private InMemoryModelCallLogPort logPort;

    private ModelCatalogPort catalog;

    private ModelCallTraceHolder traceHolder;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeOpenAiServer();
        server.respondWith(200, chatResponse("pong"));
        logPort = new InMemoryModelCallLogPort();
        catalog = pricedCatalog();
        traceHolder = new ModelCallTraceHolder();
        invoker = invoker(server.baseUrl(), 10000);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void chatShouldCallOnceAndReturnContentUsageAndModel() {
        GenerationResult result = invoker.chat(VENDOR, request(OutputFormat.TEXT, null));

        assertEquals("pong", result.getContent());
        assertEquals(3, result.getUsage().getPromptTokens());
        assertEquals(2, result.getUsage().getCompletionTokens());
        assertEquals(5, result.getUsage().getTotalTokens());
        assertEquals(MODEL, result.getModel());
        assertNull(result.getModelVersion());
        assertNull(result.getJson());
        assertEquals(1, server.requestCount());
    }

    @Test
    void textOutputShouldPassWithoutValidation() {
        server.respondWith(200, chatResponse("这不是 JSON，也不做结构校验"));

        GenerationResult result = invoker.chat(VENDOR, request(OutputFormat.TEXT, null));

        assertEquals("这不是 JSON，也不做结构校验", result.getContent());
        assertNull(result.getJson());
    }

    @Test
    void jsonObjectOutputShouldFillParsedTree() {
        server.respondWith(200, chatResponse("{\"name\":\"张三\",\"age\":3}"));

        GenerationResult result = invoker.chat(VENDOR, request(OutputFormat.JSON_OBJECT, null));

        Map<?, ?> tree = assertInstanceOf(Map.class, result.getJson());
        assertEquals("张三", tree.get("name"));
        assertEquals(3, tree.get("age"));
        assertEquals("{\"name\":\"张三\",\"age\":3}", result.getContent());
    }

    @Test
    void jsonSchemaOutputSatisfyingSchemaShouldFillParsedTree() {
        server.respondWith(200, chatResponse("{\"name\":\"张三\",\"age\":3}"));

        GenerationResult result = invoker.chat(VENDOR, request(OutputFormat.JSON_SCHEMA, SCHEMA));

        Map<?, ?> tree = assertInstanceOf(Map.class, result.getJson());
        assertEquals("张三", tree.get("name"));
        assertEquals(3, tree.get("age"));
        assertEquals(1, server.requestCount());
    }

    @Test
    void jsonObjectOutputNotJsonShouldFailValidation() {
        server.respondWith(200, chatResponse("not-json"));

        KnowledgeException error = chatError(request(OutputFormat.JSON_OBJECT, null));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
        assertEquals(40469, error.getCode());
        assertTrue(invoker.canFallback(error));
        assertEquals(1, server.requestCount());
    }

    @Test
    void jsonSchemaOutputViolatingSchemaShouldFailValidation() {
        server.respondWith(200, chatResponse("{\"age\":\"三\"}"));

        KnowledgeException error = chatError(request(OutputFormat.JSON_SCHEMA, SCHEMA));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
        assertTrue(error.getMessage().contains("schema"), error.getMessage());
        assertTrue(invoker.canFallback(error));
    }

    @Test
    void jsonSchemaWithoutSchemaShouldBeRejectedBeforeRequest() {
        KnowledgeException error = chatError(request(OutputFormat.JSON_SCHEMA, "  "));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("schema"), error.getMessage());
        assertEquals(0, server.requestCount());
    }

    @Test
    void blankVendorCodeShouldBeRejectedBeforeRequest() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> invoker.chat("  ", request(OutputFormat.TEXT, null)));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        assertEquals(0, server.requestCount());
    }

    @Test
    void nullRequestShouldBeRejectedBeforeRequest() {
        KnowledgeException error = assertThrows(KnowledgeException.class, () -> invoker.chat(VENDOR, null));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
        assertEquals(0, server.requestCount());
    }

    @Test
    void unknownVendorShouldBeRejectedByRegistry() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> invoker.chat("nope", request(OutputFormat.TEXT, null)));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        assertTrue(error.getMessage().contains("nope"), error.getMessage());
        assertEquals(0, server.requestCount());
    }

    @Test
    void blankModelKeyShouldBeRejectedByRegistry() {
        ChatRequest request = request(OutputFormat.TEXT, null);
        request.setModelKey("  ");

        KnowledgeException error = chatError(request);

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
        assertEquals(0, server.requestCount());
    }

    @Test
    void rateLimitedShouldMapToRateLimitedAndAllowFallback() {
        server.respondWith(429, errorBody("Throttling", "Requests rate limit exceeded"));

        KnowledgeException error = chatError(request(OutputFormat.TEXT, null));

        assertEquals(ErrorCode.MODEL_RATE_LIMITED, error.getErrorCode());
        assertInstanceOf(RateLimitException.class, error.getCause());
        assertTrue(invoker.canFallback(error));
        assertTrue(invoker.canFallback(error.getCause()));
        assertEquals(1, server.requestCount());
    }

    @Test
    void authFailedShouldMapToAuthFailedAndBlockFallback() {
        server.respondWith(401, errorBody("InvalidApiKey", "Invalid API-key provided"));

        KnowledgeException error = chatError(request(OutputFormat.TEXT, null));

        assertEquals(ErrorCode.MODEL_AUTH_FAILED, error.getErrorCode());
        assertInstanceOf(AuthenticationException.class, error.getCause());
        assertFalse(invoker.canFallback(error));
        assertEquals(1, server.requestCount());
    }

    @Test
    void badRequestShouldMapToBadRequestAndBlockFallback() {
        server.respondWith(400, errorBody("InvalidParameter", "Range of input length should be [1, 6000]"));

        KnowledgeException error = chatError(request(OutputFormat.TEXT, null));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
        assertInstanceOf(InvalidRequestException.class, error.getCause());
        assertFalse(invoker.canFallback(error));
    }

    @Test
    void modelNotFoundShouldMapToNotFoundAndBlockFallback() {
        server.respondWith(404, errorBody("ModelNotFound", "The model does not exist"));

        KnowledgeException error = chatError(request(OutputFormat.TEXT, null));

        assertEquals(ErrorCode.MODEL_NOT_FOUND, error.getErrorCode());
        assertInstanceOf(ModelNotFoundException.class, error.getCause());
        assertFalse(invoker.canFallback(error));
    }

    @Test
    void contentFilteredShouldMapToBadRequestAndBlockFallback() {
        server.respondWith(200, refusalResponse("DataInspectionFailed"));

        KnowledgeException error = chatError(request(OutputFormat.TEXT, null));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode(), String.valueOf(error.getCause()));
        assertInstanceOf(ContentFilteredException.class, error.getCause());
        assertFalse(invoker.canFallback(error));
    }

    @Test
    void serverErrorShouldMapToServerErrorAndAllowFallback() {
        server.respondWith(500, errorBody("InternalError", "Internal server error"));

        KnowledgeException error = chatError(request(OutputFormat.TEXT, null));

        assertEquals(ErrorCode.MODEL_SERVER_ERROR, error.getErrorCode());
        assertInstanceOf(InternalServerException.class, error.getCause());
        assertTrue(invoker.canFallback(error));
    }

    @Test
    void responseWithoutChoicesShouldMapToServerErrorAndAllowFallback() {
        server.respondWith(200, "{\"id\":\"c9\",\"model\":\"" + MODEL + "\"}");

        KnowledgeException error = chatError(request(OutputFormat.TEXT, null));

        assertEquals(ErrorCode.MODEL_SERVER_ERROR, error.getErrorCode());
        assertInstanceOf(InternalServerException.class, error.getCause());
        assertTrue(invoker.canFallback(error));
        assertEquals(1, server.requestCount());
    }

    @Test
    void timeoutShouldMapToTimeoutAndAllowFallback() {
        invoker = invoker(server.baseUrl(), 300);
        server.delayMillis(1500);

        KnowledgeException error = chatError(request(OutputFormat.TEXT, null));

        assertEquals(ErrorCode.MODEL_TIMEOUT, error.getErrorCode());
        assertInstanceOf(TimeoutException.class, error.getCause());
        assertTrue(invoker.canFallback(error));
    }

    @Test
    void unparsableResponseBodyShouldMapToOutputInvalidAndAllowFallback() {
        server.respondWith(200, "not-json");

        KnowledgeException error = chatError(request(OutputFormat.TEXT, null));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
        assertTrue(hasCause(error, JsonProcessingException.class), String.valueOf(error.getCause()));
        assertTrue(invoker.canFallback(error));
        assertEquals(1, server.requestCount());
    }

    @Test
    void connectionRefusedShouldMapToUnreachableAndAllowFallback() throws IOException {
        invoker = invoker("http://127.0.0.1:" + closedPort() + "/v1", 5000);

        KnowledgeException error = chatError(request(OutputFormat.TEXT, null));

        assertEquals(ErrorCode.MODEL_UNREACHABLE, error.getErrorCode());
        assertTrue(hasCause(error, ConnectException.class), String.valueOf(error.getCause()));
        assertTrue(invoker.canFallback(error));
        assertEquals(0, server.requestCount());
    }

    @Test
    void mappedDetailShouldCarryVendorModelAndVendorMessage() {
        server.respondWith(429, errorBody("Throttling", "Requests rate limit exceeded"));

        KnowledgeException error = chatError(request(OutputFormat.TEXT, null));

        assertTrue(error.getMessage().contains(VENDOR), error.getMessage());
        assertTrue(error.getMessage().contains(MODEL), error.getMessage());
        assertTrue(error.getMessage().contains("Requests rate limit exceeded"), error.getMessage());
    }

    @Test
    void embedShouldCallOnceAndMapVectorsDimensionModelAndRequestId() {
        server.respondWith(200, EMBEDDING_RESPONSE);

        EmbeddingResult result = invoker.embed(VENDOR, embedRequest(), new ModelCallTrace());

        assertEquals(2, result.getEmbeddings().size());
        assertEquals(2, result.getDimension());
        assertEquals(MODEL, result.getModel());
        assertEquals("req-embed", result.getRequestId());
        assertEquals(1, server.requestCount());
    }

    @Test
    void embedWithoutVectorDataShouldMapToOutputInvalid() {
        server.respondWith(200, "{\"object\":\"list\",\"model\":\"" + MODEL + "\"}");

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> invoker.embed(VENDOR, embedRequest(), new ModelCallTrace()));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
        assertEquals(40469, error.getCode());
        assertTrue(invoker.canFallback(error));
        assertEquals(1, server.requestCount());
    }

    @Test
    void embedEmptyVectorListShouldMapToOutputInvalid() {
        invoker = invoker(new EmptyEmbeddingAdapter());

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> invoker.embed(VENDOR, embedRequest(), new ModelCallTrace()));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
        assertTrue(error.getMessage().contains(MODEL), error.getMessage());
    }

    @Test
    void embedServerErrorShouldMapToServerErrorLikeChat() {
        server.respondWith(500, errorBody("InternalError", "Internal server error"));

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> invoker.embed(VENDOR, embedRequest(), new ModelCallTrace()));

        assertEquals(ErrorCode.MODEL_SERVER_ERROR, error.getErrorCode());
        assertInstanceOf(InternalServerException.class, error.getCause());
        assertTrue(invoker.canFallback(error));
    }

    @Test
    void embedFailedCallShouldRecordErrorCodeOnTrace() {
        server.respondWith(429, errorBody("Throttling", "Requests rate limit exceeded"));
        ModelCallTrace trace = new ModelCallTrace();

        assertThrows(KnowledgeException.class, () -> invoker.embed(VENDOR, embedRequest(), trace));

        assertEquals(ErrorCode.MODEL_RATE_LIMITED, trace.errorCode());
    }

    @Test
    void embedBlankVendorCodeShouldBeRejectedBeforeRequest() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> invoker.embed("  ", embedRequest(), new ModelCallTrace()));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        assertEquals(0, server.requestCount());
    }

    @Test
    void embedNullRequestShouldBeRejectedBeforeRequest() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> invoker.embed(VENDOR, null, new ModelCallTrace()));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
        assertEquals(0, server.requestCount());
    }

    @Test
    void visionShouldCallOnceAndReturnContentUsageAndModel() {
        GenerationResult result = invoker.vision(VENDOR, visionRequest(OutputFormat.TEXT, null), new ModelCallTrace());

        assertEquals("pong", result.getContent());
        assertEquals(3, result.getUsage().getPromptTokens());
        assertEquals(5, result.getUsage().getTotalTokens());
        assertEquals(MODEL, result.getModel());
        assertNull(result.getJson());
        assertEquals(1, server.requestCount());
    }

    @Test
    void visionJsonSchemaOutputShouldFillParsedTree() {
        server.respondWith(200, chatResponse("{\"lines\":[{\"text\":\"甲\"}]}"));

        GenerationResult result = invoker.vision(VENDOR, visionRequest(OutputFormat.JSON_SCHEMA, VISION_SCHEMA),
                new ModelCallTrace());

        Map<?, ?> tree = assertInstanceOf(Map.class, result.getJson());
        assertEquals(1, ((List<?>) tree.get("lines")).size());
        assertEquals(1, server.requestCount());
    }

    @Test
    void visionJsonSchemaOutputViolatingSchemaShouldFailValidation() {
        server.respondWith(200, chatResponse("{\"lines\":[{\"text\":3}]}"));

        KnowledgeException error = visionError(visionRequest(OutputFormat.JSON_SCHEMA, VISION_SCHEMA));

        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, error.getErrorCode());
        assertTrue(error.getMessage().contains("schema"), error.getMessage());
        assertEquals(1, server.requestCount());
    }

    @Test
    void visionWithoutSchemaShouldBeRejectedBeforeRequest() {
        KnowledgeException error = visionError(visionRequest(OutputFormat.JSON_SCHEMA, "  "));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
        assertEquals(0, server.requestCount());
    }

    @Test
    void visionBlankVendorCodeShouldBeRejectedBeforeRequest() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> invoker.vision("  ", visionRequest(OutputFormat.TEXT, null), new ModelCallTrace()));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        assertEquals(0, server.requestCount());
    }

    @Test
    void visionNullRequestShouldBeRejectedBeforeRequest() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> invoker.vision(VENDOR, null, new ModelCallTrace()));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
        assertEquals(0, server.requestCount());
    }

    @Test
    void visionRateLimitedShouldMapToRateLimitedAndAllowFallback() {
        server.respondWith(429, errorBody("Throttling", "Requests rate limit exceeded"));

        KnowledgeException error = visionError(visionRequest(OutputFormat.TEXT, null));

        assertEquals(ErrorCode.MODEL_RATE_LIMITED, error.getErrorCode());
        assertTrue(invoker.canFallback(error));
        assertEquals(1, server.requestCount());
    }

    @Test
    void visionSuccessShouldWriteOneCallLogRowWithTableStructureCapability() {
        GenerationResult result = invoker.vision(VENDOR, visionRequest(OutputFormat.TEXT, null), new ModelCallTrace());

        assertEquals("pong", result.getContent());
        assertEquals(1, logPort.entries().size());
        ModelCallLogEntry entry = logPort.lastEntry();
        assertEquals(ModelCapability.TABLE_STRUCTURE, entry.getCapability());
        assertEquals(VENDOR, entry.getVendor());
        assertEquals(MODEL, entry.getModel());
        assertEquals(CallStatus.SUCCESS, entry.getStatus());
        // 表格结构的计费单位未定：用量与成本一律按未知记（D34）
        assertEquals(1, entry.getUsageUnknown());
        assertNull(entry.getTotalTokens());
        assertNull(entry.getCost());
        assertNotNull(entry.getLatencyMs());
    }

    @Test
    void visionRejectedBeforeRequestShouldWriteNoCallLogRow() {
        assertThrows(KnowledgeException.class,
                () -> invoker.vision(VENDOR, null, new ModelCallTrace()));
        assertThrows(KnowledgeException.class,
                () -> invoker.vision(VENDOR, visionRequest(OutputFormat.JSON_SCHEMA, "  "), new ModelCallTrace()));

        assertTrue(logPort.entries().isEmpty(), "请求未发出的失败不落明细");
        assertEquals(0, server.requestCount());
    }

    @Test
    void chatSuccessShouldWriteOneCallLogRowWithTokensAndCost() {
        GenerationResult result = invoker.chat(VENDOR, request(OutputFormat.TEXT, null));

        assertEquals("pong", result.getContent());
        assertEquals(1, logPort.entries().size());
        ModelCallLogEntry entry = logPort.lastEntry();
        assertEquals(ModelCapability.CHAT, entry.getCapability());
        assertEquals(VENDOR, entry.getVendor());
        assertEquals(MODEL, entry.getModel());
        assertEquals(CallStatus.SUCCESS, entry.getStatus());
        assertNull(entry.getErrorCode());
        assertEquals(0, entry.getUsageUnknown());
        assertEquals(3L, entry.getPromptTokens());
        assertEquals(2L, entry.getCompletionTokens());
        assertEquals(5L, entry.getTotalTokens());
        // 3 × 2.000000 / 1e6 + 2 × 8.000000 / 1e6 = 0.000022
        assertEquals(new BigDecimal("0.000022"), entry.getCost());
        assertEquals(new BigDecimal("2.000000"), entry.getUnitPriceIn());
        assertEquals(new BigDecimal("8.000000"), entry.getUnitPriceOut());
        assertEquals("CNY", entry.getCurrency());
        assertEquals(0, entry.getRetryCount());
        assertNotNull(entry.getLatencyMs());
    }

    @Test
    void embedSuccessShouldWriteOneCallLogRowWithEmbeddingCapability() {
        server.respondWith(200, EMBEDDING_RESPONSE);

        invoker.embed(VENDOR, embedRequest(), new ModelCallTrace());

        assertEquals(1, logPort.entries().size());
        ModelCallLogEntry entry = logPort.lastEntry();
        assertEquals(ModelCapability.EMBEDDING, entry.getCapability());
        assertEquals(CallStatus.SUCCESS, entry.getStatus());
        assertEquals(0, entry.getUsageUnknown());
        assertEquals(3L, entry.getPromptTokens());
        assertNull(entry.getCompletionTokens());
        // 嵌入按千次计价，token 单价口径不适用，成本留空
        assertNull(entry.getCost());
        assertEquals(1, server.requestCount());
    }

    @Test
    void rateLimitedCallShouldWriteOneFailedCallLogRow() {
        server.respondWith(429, errorBody("Throttling", "Requests rate limit exceeded"));

        chatError(request(OutputFormat.TEXT, null));

        assertEquals(1, logPort.entries().size());
        ModelCallLogEntry entry = logPort.lastEntry();
        assertEquals(CallStatus.RATE_LIMITED, entry.getStatus());
        assertEquals("MODEL_RATE_LIMITED", entry.getErrorCode());
        assertEquals(1, entry.getUsageUnknown());
        assertNull(entry.getPromptTokens());
        assertNull(entry.getCost());
    }

    @Test
    void serverErrorCallShouldWriteOneFailedCallLogRow() {
        server.respondWith(500, errorBody("InternalError", "Internal server error"));

        chatError(request(OutputFormat.TEXT, null));

        assertEquals(CallStatus.FAILED, logPort.lastEntry().getStatus());
        assertEquals("MODEL_SERVER_ERROR", logPort.lastEntry().getErrorCode());
        assertEquals(1, server.requestCount());
    }

    @Test
    void timeoutCallShouldWriteOneTimeoutCallLogRow() {
        invoker = invoker(server.baseUrl(), 300);
        server.delayMillis(1500);

        chatError(request(OutputFormat.TEXT, null));

        assertEquals(CallStatus.TIMEOUT, logPort.lastEntry().getStatus());
        assertEquals("MODEL_TIMEOUT", logPort.lastEntry().getErrorCode());
    }

    @Test
    void outputInvalidCallShouldWriteOneFailedCallLogRowWithUsage() {
        server.respondWith(200, chatResponse("not-json"));

        chatError(request(OutputFormat.JSON_OBJECT, null));

        ModelCallLogEntry entry = logPort.lastEntry();
        assertEquals(CallStatus.FAILED, entry.getStatus());
        assertEquals("MODEL_OUTPUT_INVALID", entry.getErrorCode());
        assertEquals(0, entry.getUsageUnknown());
        assertEquals(5L, entry.getTotalTokens());
    }

    @Test
    void rejectedBeforeRequestShouldWriteNoCallLogRow() {
        assertThrows(KnowledgeException.class, () -> invoker.chat("  ", request(OutputFormat.TEXT, null)));
        assertThrows(KnowledgeException.class, () -> invoker.chat(VENDOR, null));
        assertThrows(KnowledgeException.class, () -> invoker.chat(VENDOR, request(OutputFormat.JSON_SCHEMA, "  ")));
        assertThrows(KnowledgeException.class, () -> invoker.embed("  ", embedRequest(), new ModelCallTrace()));

        assertTrue(logPort.entries().isEmpty(), "请求未发出的失败不落明细");
        assertEquals(0, server.requestCount());
    }

    /** 调用并断言抛出知识异常 */
    private KnowledgeException chatError(ChatRequest request) {
        return assertThrows(KnowledgeException.class, () -> invoker.chat(VENDOR, request));
    }

    /** 调用并断言抛出知识异常（视觉入参） */
    private KnowledgeException visionError(VisionRequest request) {
        return assertThrows(KnowledgeException.class, () -> invoker.vision(VENDOR, request, new ModelCallTrace()));
    }

    /** 视觉入参：图像字节（带媒体类型）+ 提示词 + 指定输出约束 */
    private VisionRequest visionRequest(OutputFormat format, String schema) {
        VisionRequest request = new VisionRequest();
        request.setModelKey(MODEL);
        request.setImageBytes(new byte[]{1, 2, 3, 4});
        request.setMimeType("image/png");
        request.setPrompt("按图给出逐行文本");
        OutputSpec output = new OutputSpec();
        output.setFormat(format);
        output.setSchema(schema);
        request.setOutput(output);
        return request;
    }

    /** 向量入参：两条文本 + 模型名 + requestId */
    private EmbeddingRequest embedRequest() {
        EmbeddingRequest request = new EmbeddingRequest();
        request.setModel(MODEL);
        request.setTexts(List.of("第一句", "第二句"));
        request.setRequestId("req-embed");
        return request;
    }

    /** 带假服务的调用器：重试为 0（失败即抛，请求次数与调用次数一致）；调用上下文持有器与适配器、监听器共用一份 */
    private ModelInvoker invoker(String baseUrl, int timeoutMs) {
        ModelVendorProperties properties = new ModelVendorProperties();
        Map<String, ModelVendorProperties.Vendor> vendors = new LinkedHashMap<>();
        ModelVendorProperties.Vendor vendor = new ModelVendorProperties.Vendor();
        vendor.setBaseUrl(baseUrl);
        vendor.setApiKey("sk-invoker");
        vendor.setTimeoutMs(timeoutMs);
        vendor.setMaxRetries(0);
        vendors.put(VENDOR, vendor);
        properties.setVendors(vendors);
        OpenAiCompatibleEngineFactory engineFactory =
                new OpenAiCompatibleEngineFactory(new UsageRecordingListener(traceHolder), traceHolder);
        ModelInstanceRegistry registry = new ModelInstanceRegistry(properties, engineFactory, catalog);
        return invoker(new OpenAiCompatibleAdapter(registry, traceHolder));
    }

    /** 指定适配器的调用器：上下文持有器按用例新建，向量出参的结构守卫按它验证 */
    private ModelInvoker invoker(ModelAdapter adapter) {
        return TestModelInvoker.of(adapter, traceHolder, logPort, catalog);
    }

    /** 带价格快照的目录：模型 → 厂商解析与对话定价都取它 */
    private ModelCatalogPort pricedCatalog() {
        ModelCatalogPort pricedCatalog = mock(ModelCatalogPort.class);
        when(pricedCatalog.findUniqueEnabledByModelKey(MODEL)).thenReturn(descriptor());
        when(pricedCatalog.findByVendorAndModelKey(VENDOR, MODEL)).thenReturn(descriptor());
        return pricedCatalog;
    }

    /** 目录项：对话能力 + 输入 2.000000 / 输出 8.000000（每百万 token，CNY） */
    private ModelDescriptor descriptor() {
        ModelDescriptor descriptor = new ModelDescriptor();
        descriptor.setVendorCode(VENDOR);
        descriptor.setModelKey(MODEL);
        descriptor.setCapabilities(Set.of(ModelCapability.CHAT));
        descriptor.setPriceInPerM(new BigDecimal("2.000000"));
        descriptor.setPriceOutPerM(new BigDecimal("8.000000"));
        descriptor.setCurrency("CNY");
        return descriptor;
    }

    /** 向量出参为空的适配器：验证调用器对「响应缺向量数据」的结构守卫 */
    private static final class EmptyEmbeddingAdapter implements ModelAdapter {

        @Override
        public Set<AuthType> supportedAuthTypes() {
            return Set.of(AuthType.BEARER);
        }

        @Override
        public GenerationResult chat(String vendorCode, ChatRequest request) {
            throw new KnowledgeException(ErrorCode.MODEL_MODALITY_UNSUPPORTED, "模态：chat");
        }

        @Override
        public EmbeddingResult embed(String vendorCode, EmbeddingRequest request) {
            EmbeddingResult result = new EmbeddingResult();
            result.setEmbeddings(List.of());
            result.setModel(request.getModel());
            return result;
        }
    }

    /** 对话入参：单条用户消息 + 指定输出约束 */
    private ChatRequest request(OutputFormat format, String schema) {
        ChatRequest request = new ChatRequest();
        request.setModelKey(MODEL);
        List<ChatMessage> messages = new ArrayList<>();
        ChatMessage message = new ChatMessage();
        message.setRole(MessageRole.USER);
        message.setContent("ping");
        messages.add(message);
        request.setMessages(messages);
        OutputSpec output = new OutputSpec();
        output.setFormat(format);
        output.setSchema(schema);
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

    /** 内容审核应答：助手消息只带 refusal 字段 */
    private String refusalResponse(String refusal) {
        return "{\"id\":\"c2\",\"model\":\"" + MODEL + "\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":null,\"refusal\":" + JSONUtil.quote(refusal) + "},\"finish_reason\":\"stop\"}]}";
    }

    /** 错误应答体（OpenAI 兼容形态） */
    private String errorBody(String code, String message) {
        return "{\"error\":{\"code\":" + JSONUtil.quote(code) + ",\"message\":" + JSONUtil.quote(message)
                + ",\"type\":\"invalid_request_error\"}}";
    }

    /** 取一个刚释放的本机端口，连接该端口即被拒 */
    private int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
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
