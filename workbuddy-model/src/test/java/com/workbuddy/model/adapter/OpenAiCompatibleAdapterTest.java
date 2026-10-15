package com.workbuddy.model.adapter;

import com.workbuddy.common.domain.model.AuthType;
import com.workbuddy.common.domain.model.ChatMessage;
import com.workbuddy.common.domain.model.ChatRequest;
import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.MessageRole;
import com.workbuddy.common.domain.model.OutputFormat;
import com.workbuddy.common.domain.model.OutputSpec;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.model.config.ModelVendorProperties;
import com.workbuddy.model.engine.ModelInstanceRegistry;
import com.workbuddy.model.engine.OpenAiCompatibleEngineFactory;
import com.workbuddy.model.listener.ModelCallTraceHolder;
import com.workbuddy.model.listener.UsageRecordingListener;
import com.workbuddy.model.support.FakeOpenAiServer;
import com.workbuddy.model.support.TestModelCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OpenAI 兼容适配器单测（假 OpenAI 兼容端点）：请求消息与输出约束的组装、响应到 GenerationResult 的映射、
 * 用量的三项可空口径、JSON_SCHEMA 缺 schema 的调用前拒绝。
 *
 * @author cxxl
 */
class OpenAiCompatibleAdapterTest {

    private static final String FULL_RESPONSE = "{\"id\":\"c1\",\"model\":\"qwen-max\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"pong\"},"
            + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}";

    private FakeOpenAiServer server;

    private OpenAiCompatibleAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeOpenAiServer();
        server.respondWith(200, FULL_RESPONSE);
        ModelVendorProperties properties = new ModelVendorProperties();
        Map<String, ModelVendorProperties.Vendor> vendors = new LinkedHashMap<>();
        vendors.put("dashscope", vendor());
        properties.setVendors(vendors);
        ModelCallTraceHolder traceHolder = new ModelCallTraceHolder();
        adapter = new OpenAiCompatibleAdapter(
                new ModelInstanceRegistry(properties,
                        new OpenAiCompatibleEngineFactory(new UsageRecordingListener(traceHolder), traceHolder),
                        new TestModelCatalog()),
                traceHolder);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void supportedAuthTypesShouldCoverBearer() {
        assertEquals(Set.of(AuthType.BEARER), adapter.supportedAuthTypes());
    }

    @Test
    void chatShouldSendMessagesModelAndBearerHeader() {
        adapter.chat("dashscope", request(OutputFormat.TEXT, null));

        assertEquals(FakeOpenAiServer.CHAT_PATH, server.lastPath());
        assertEquals("Bearer sk-adapter", server.lastAuthorization());
        String body = server.lastBody();
        assertTrue(body.contains("\"qwen-max\""), body);
        assertTrue(body.contains("\"role\" : \"system\""), body);
        assertTrue(body.contains("\"你是助手\""), body);
        assertTrue(body.contains("\"role\" : \"user\""), body);
        assertTrue(body.contains("\"role\" : \"assistant\""), body);
    }

    @Test
    void chatShouldMapContentUsageAndModel() {
        GenerationResult result = adapter.chat("dashscope", request(OutputFormat.TEXT, null));

        assertEquals("pong", result.getContent());
        assertEquals(3, result.getUsage().getPromptTokens());
        assertEquals(2, result.getUsage().getCompletionTokens());
        assertEquals(5, result.getUsage().getTotalTokens());
        assertEquals("qwen-max", result.getModel());
        assertNull(result.getModelVersion());
        assertNull(result.getJson());
    }

    @Test
    void chatShouldLeaveUsageNullWhenVendorOmitsIt() {
        server.respondWith(200, "{\"id\":\"c2\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"pong\"},\"finish_reason\":\"stop\"}]}");

        GenerationResult result = adapter.chat("dashscope", request(OutputFormat.TEXT, null));

        assertEquals("pong", result.getContent());
        assertNull(result.getUsage());
    }

    @Test
    void chatShouldKeepMissingUsageFieldsNull() {
        server.respondWith(200, "{\"id\":\"c3\",\"model\":\"qwen-max\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"pong\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":7}}");

        GenerationResult result = adapter.chat("dashscope", request(OutputFormat.TEXT, null));

        assertEquals(7, result.getUsage().getPromptTokens());
        assertNull(result.getUsage().getCompletionTokens());
        assertNull(result.getUsage().getTotalTokens());
    }

    @Test
    void chatShouldFallBackToRequestModelNameWhenResponseOmitsIt() {
        server.respondWith(200, "{\"id\":\"c4\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"pong\"},\"finish_reason\":\"stop\"}]}");

        GenerationResult result = adapter.chat("dashscope", request(OutputFormat.TEXT, null));

        assertEquals("qwen-max", result.getModel());
    }

    @Test
    void textOutputShouldNotSendResponseFormat() {
        adapter.chat("dashscope", request(OutputFormat.TEXT, null));

        assertFalse(server.lastBody().contains("response_format"), server.lastBody());
    }

    @Test
    void jsonObjectOutputShouldSendJsonObjectFormat() {
        adapter.chat("dashscope", request(OutputFormat.JSON_OBJECT, null));

        String body = server.lastBody();
        assertTrue(body.contains("\"response_format\""), body);
        assertTrue(body.contains("\"json_object\""), body);
    }

    @Test
    void jsonSchemaOutputShouldSendRawSchema() {
        adapter.chat("dashscope", request(OutputFormat.JSON_SCHEMA, "{\"type\":\"object\"}"));

        String body = server.lastBody();
        assertTrue(body.contains("\"response_format\""), body);
        assertTrue(body.contains("\"json_schema\""), body);
        assertTrue(body.contains("\"name\" : \"output\""), body);
        assertTrue(body.contains("\"schema\""), body);
        assertTrue(body.contains("\"type\" : \"object\""), body);
    }

    @Test
    void jsonSchemaOutputWithoutSchemaShouldFailBeforeRequest() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> adapter.chat("dashscope", request(OutputFormat.JSON_SCHEMA, "  ")));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("schema"), error.getMessage());
        assertEquals(0, server.requestCount());
    }

    /** 单厂商配置：地址指向假服务，重试为 0（失败即抛，不放大请求次数） */
    private ModelVendorProperties.Vendor vendor() {
        ModelVendorProperties.Vendor vendor = new ModelVendorProperties.Vendor();
        vendor.setBaseUrl(server.baseUrl());
        vendor.setApiKey("sk-adapter");
        vendor.setTimeoutMs(10000);
        vendor.setMaxRetries(0);
        return vendor;
    }

    /** 对话入参：三段消息（系统 / 用户 / 助手）+ 指定输出约束 */
    private ChatRequest request(OutputFormat format, String schema) {
        ChatRequest request = new ChatRequest();
        request.setModelKey("qwen-max");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(message(MessageRole.SYSTEM, "你是助手"));
        messages.add(message(MessageRole.USER, "ping"));
        messages.add(message(MessageRole.ASSISTANT, "旧答"));
        request.setMessages(messages);
        OutputSpec output = new OutputSpec();
        output.setFormat(format);
        output.setSchema(schema);
        request.setOutput(output);
        request.setTimeoutMs(10000);
        return request;
    }

    /** 单条消息 */
    private ChatMessage message(MessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
