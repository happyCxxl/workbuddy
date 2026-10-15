package com.workbuddy.model.engine;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.model.config.ModelVendorProperties;
import com.workbuddy.model.listener.ModelCallTraceHolder;
import com.workbuddy.model.listener.UsageRecordingListener;
import com.workbuddy.model.support.TestModelCatalog;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实例注册表单测：同键复用、异键新实例、对话与向量分表、未知厂商与空模型名的拒绝口径、只读查询。
 * 实例构建不发起调用，地址取本机保留端口，用例不触网。
 *
 * @author cxxl
 */
class ModelInstanceRegistryTest {

    private ModelInstanceRegistry registry;

    private TestModelCatalog catalog;

    @BeforeEach
    void setUp() {
        ModelVendorProperties properties = new ModelVendorProperties();
        Map<String, ModelVendorProperties.Vendor> vendors = new LinkedHashMap<>();
        vendors.put("dashscope", vendor("http://127.0.0.1:1/v1", "sk-dashscope"));
        vendors.put("custom", vendor("http://127.0.0.1:2/v1", "sk-custom"));
        properties.setVendors(vendors);
        ModelCallTraceHolder traceHolder = new ModelCallTraceHolder();
        catalog = new TestModelCatalog().withModel("text-embedding-v4", "dashscope");
        registry = new ModelInstanceRegistry(properties,
                new OpenAiCompatibleEngineFactory(new UsageRecordingListener(traceHolder), traceHolder), catalog);
    }

    @Test
    void sameVendorAndModelShouldReuseInstance() {
        ChatModel first = registry.chatModel("dashscope", "qwen-max");
        ChatModel second = registry.chatModel("dashscope", "qwen-max");

        assertSame(first, second);
        assertTrue(registry.chatInstanceBuilt("dashscope", "qwen-max"));
        assertEquals(1, registry.instanceCount());
    }

    @Test
    void differentModelKeyShouldBuildNewInstance() {
        ChatModel qwenMax = registry.chatModel("dashscope", "qwen-max");
        ChatModel qwenPlus = registry.chatModel("dashscope", "qwen-plus");

        assertNotSame(qwenMax, qwenPlus);
        assertEquals(2, registry.instanceCount());
    }

    @Test
    void differentVendorShouldBuildNewInstance() {
        ChatModel dashscope = registry.chatModel("dashscope", "qwen-max");
        ChatModel custom = registry.chatModel("custom", "qwen-max");

        assertNotSame(dashscope, custom);
        assertEquals(2, registry.instanceCount());
    }

    @Test
    void embeddingInstancesShouldBeCachedInTheirOwnTable() {
        EmbeddingModel first = registry.embeddingModel("text-embedding-v4");
        EmbeddingModel second = registry.embeddingModel("text-embedding-v4");

        assertSame(first, second);
        assertTrue(registry.embeddingInstanceBuilt("dashscope", "text-embedding-v4"));
        assertFalse(registry.chatInstanceBuilt("dashscope", "text-embedding-v4"));
        assertEquals(1, registry.instanceCount());
    }

    @Test
    void embeddingVendorShouldComeFromCatalogWhenMultipleVendorsConfigured() {
        assertEquals("dashscope", registry.resolveVendorCode("text-embedding-v4"));

        EmbeddingModel model = registry.embeddingModel("text-embedding-v4");

        assertNotNull(model);
        assertTrue(registry.embeddingInstanceBuilt("dashscope", "text-embedding-v4"));
    }

    @Test
    void embeddingVendorShouldBeRejectedWhenCatalogMissesModelAndVendorsAreAmbiguous() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> registry.resolveVendorCode("custom-embed"));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        assertTrue(error.getMessage().contains("custom-embed"), error.getMessage());
        assertTrue(error.getMessage().contains("dashscope"), error.getMessage());
        assertEquals(0, registry.instanceCount());
    }

    @Test
    void embeddingVendorShouldFallBackToTheOnlyConfiguredVendor() {
        ModelInstanceRegistry single = singleVendorRegistry();

        assertEquals("only", single.resolveVendorCode("custom-embed"));
        assertNotNull(single.embeddingModel("custom-embed"));
        assertTrue(single.embeddingInstanceBuilt("only", "custom-embed"));
    }

    @Test
    void blankEmbeddingModelKeyShouldBeRejected() {
        KnowledgeException error = assertThrows(KnowledgeException.class, () -> registry.resolveVendorCode(" "));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
        assertEquals(0, registry.instanceCount());
    }

    @Test
    void unknownVendorShouldThrowWithVendorCodeAndConfiguredList() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> registry.chatModel("nope", "qwen-max"));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN.getCode(), error.getCode());
        assertTrue(error.getMessage().contains("nope"), error.getMessage());
        assertTrue(error.getMessage().contains("dashscope"), error.getMessage());
        assertEquals(0, registry.instanceCount());
    }

    @Test
    void blankModelKeyShouldBeRejected() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> registry.chatModel("dashscope", "  "));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
        assertTrue(error.getMessage().contains("dashscope"), error.getMessage());
        assertEquals(0, registry.instanceCount());
    }

    /** 单厂商配置的注册表：地址指向本机保留端口，实例构建不发起调用 */
    private ModelInstanceRegistry singleVendorRegistry() {
        ModelVendorProperties properties = new ModelVendorProperties();
        properties.setVendors(Map.of("only", vendor("http://127.0.0.1:3/v1", "sk-only")));
        ModelCallTraceHolder traceHolder = new ModelCallTraceHolder();
        return new ModelInstanceRegistry(properties,
                new OpenAiCompatibleEngineFactory(new UsageRecordingListener(traceHolder), traceHolder), catalog);
    }

    /** 单厂商配置：地址指向本机保留端口，实例构建不发起调用 */
    private ModelVendorProperties.Vendor vendor(String baseUrl, String apiKey) {
        ModelVendorProperties.Vendor vendor = new ModelVendorProperties.Vendor();
        vendor.setBaseUrl(baseUrl);
        vendor.setApiKey(apiKey);
        return vendor;
    }
}
