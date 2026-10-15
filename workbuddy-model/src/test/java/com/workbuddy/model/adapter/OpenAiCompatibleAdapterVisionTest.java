package com.workbuddy.model.adapter;

import com.workbuddy.common.domain.model.GenerationResult;
import com.workbuddy.common.domain.model.ImageRegion;
import com.workbuddy.common.domain.model.OutputFormat;
import com.workbuddy.common.domain.model.OutputSpec;
import com.workbuddy.common.domain.model.VisionRequest;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视觉模态的适配器单测（假 OpenAI 兼容端点）：图像字节按请求给出的媒体类型组装成 data URL、
 * 图像引用按 URL 组装、提示词进文本内容、输出约束沿用对话模态的映射口径、用量与模型标识按同一套回填。
 *
 * @author cxxl
 */
class OpenAiCompatibleAdapterVisionTest {

    /** 图像字节：内容可辨识的四个字节 */
    private static final byte[] IMAGE_BYTES = {1, 2, 3, 4};

    /** 媒体类型：用例自带的取值，断言请求体里 data URL 的前缀按它组装 */
    private static final String JPEG = "image/jpeg";

    private static final String VISION_RESPONSE = "{\"id\":\"v1\",\"model\":\"qwen-vl-max\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"lines\\\":[]}\"},"
            + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":4,\"total_tokens\":15}}";

    private FakeOpenAiServer server;

    private OpenAiCompatibleAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeOpenAiServer();
        server.respondWith(200, VISION_RESPONSE);
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
    void visionShouldSendBase64ImageAndPromptToChatCompletions() {
        adapter.vision("dashscope", request(IMAGE_BYTES, null, JPEG, OutputFormat.TEXT, null));

        assertEquals(FakeOpenAiServer.CHAT_PATH, server.lastPath());
        assertEquals("Bearer sk-adapter", server.lastAuthorization());
        String body = server.lastBody();
        assertTrue(body.contains("\"qwen-vl-max\""), body);
        assertTrue(body.contains("\"image_url\""), body);
        assertTrue(body.contains(Base64.getEncoder().encodeToString(IMAGE_BYTES)), body);
        assertTrue(body.contains("data:image/jpeg;base64,"), body);
        assertFalse(body.contains("image/png"), body);
        assertTrue(body.contains("按图给出逐行文本"), body);
    }

    @Test
    void visionShouldSendImageRefAsUrlWhenBytesMissing() {
        adapter.vision("dashscope",
                request(null, "https://example.test/page1.jpeg", "image/png", OutputFormat.TEXT, null));

        String body = server.lastBody();
        assertTrue(body.contains("\"image_url\""), body);
        assertTrue(body.contains("https://example.test/page1.jpeg"), body);
        assertFalse(body.contains("data:image/png;base64,"), body);
        assertFalse(body.contains("data:image/jpeg;base64,"), body);
        assertFalse(body.contains("image/png"), body);
    }

    @Test
    void visionWithoutMimeTypeShouldFailBeforeRequest() {
        KnowledgeException missing = assertThrows(KnowledgeException.class,
                () -> adapter.vision("dashscope", request(IMAGE_BYTES, null, null, OutputFormat.TEXT, null)));
        KnowledgeException blank = assertThrows(KnowledgeException.class,
                () -> adapter.vision("dashscope", request(IMAGE_BYTES, null, "  ", OutputFormat.TEXT, null)));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, missing.getErrorCode());
        assertTrue(missing.getMessage().contains("媒体类型"), missing.getMessage());
        assertEquals(ErrorCode.MODEL_BAD_REQUEST, blank.getErrorCode());
        assertTrue(blank.getMessage().contains("mimeType"), blank.getMessage());
        assertEquals(0, server.requestCount());
    }

    @Test
    void visionShouldMapContentUsageAndModel() {
        GenerationResult result = adapter.vision("dashscope",
                request(IMAGE_BYTES, null, JPEG, OutputFormat.TEXT, null));

        assertEquals("{\"lines\":[]}", result.getContent());
        assertEquals(11, result.getUsage().getPromptTokens());
        assertEquals(4, result.getUsage().getCompletionTokens());
        assertEquals(15, result.getUsage().getTotalTokens());
        assertEquals("qwen-vl-max", result.getModel());
        assertNull(result.getModelVersion());
        assertNull(result.getJson());
    }

    @Test
    void visionTextOutputShouldNotSendResponseFormat() {
        adapter.vision("dashscope", request(IMAGE_BYTES, null, JPEG, OutputFormat.TEXT, null));

        assertFalse(server.lastBody().contains("response_format"), server.lastBody());
    }

    @Test
    void visionJsonSchemaOutputShouldSendRawSchema() {
        adapter.vision("dashscope",
                request(IMAGE_BYTES, null, JPEG, OutputFormat.JSON_SCHEMA, "{\"type\":\"object\"}"));

        String body = server.lastBody();
        assertTrue(body.contains("\"response_format\""), body);
        assertTrue(body.contains("\"json_schema\""), body);
        assertTrue(body.contains("\"name\" : \"output\""), body);
        assertTrue(body.contains("\"type\" : \"object\""), body);
    }

    @Test
    void visionJsonSchemaOutputWithoutSchemaShouldFailBeforeRequest() {
        KnowledgeException error = assertThrows(KnowledgeException.class, () -> adapter.vision("dashscope",
                request(IMAGE_BYTES, null, JPEG, OutputFormat.JSON_SCHEMA, "  ")));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
        assertEquals(0, server.requestCount());
    }

    @Test
    void visionWithoutImageShouldFailBeforeRequest() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> adapter.vision("dashscope", request(null, null, null, OutputFormat.TEXT, null)));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("缺图像"), error.getMessage());
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

    /** 视觉入参：模型 + 图像（字节或引用二选一）+ 媒体类型 + 提示词 + 页码区域 + 输出约束 */
    private VisionRequest request(byte[] imageBytes, String imageRef, String mimeType, OutputFormat format,
            String schema) {
        VisionRequest request = new VisionRequest();
        request.setModelKey("qwen-vl-max");
        request.setImageBytes(imageBytes);
        request.setImageRef(imageRef);
        request.setMimeType(mimeType);
        ImageRegion region = new ImageRegion();
        region.setPageNo(1);
        region.setX0(0.0D);
        region.setY0(0.0D);
        region.setX1(595.0D);
        region.setY1(842.0D);
        request.setRegion(region);
        request.setPrompt("按图给出逐行文本");
        OutputSpec output = new OutputSpec();
        output.setFormat(format);
        output.setSchema(schema);
        request.setOutput(output);
        return request;
    }
}
