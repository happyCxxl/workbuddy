package com.workbuddy.model.engine;

import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.model.config.ModelVendorProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 实例注册表单测：厂商校验、密钥校验与实例缓存复用。
 */
class ModelInstanceRegistryTest {

    private ModelVendorProperties vendorProperties;

    private ModelInstanceRegistry registry;

    @BeforeEach
    void setUp() {
        vendorProperties = new ModelVendorProperties();
        Map<String, ModelVendorProperties.Vendor> vendors = new LinkedHashMap<>();
        ModelVendorProperties.Vendor vendor = new ModelVendorProperties.Vendor();
        vendor.setBaseUrl("http://localhost:1/v1");
        vendor.setApiKey("sk-test");
        vendor.setTimeoutMs(1000);
        vendors.put("dashscope", vendor);
        vendorProperties.setVendors(vendors);
        registry = new ModelInstanceRegistry(vendorProperties);
    }

    private ModelDescriptor model(String vendorCode, String modelKey) {
        ModelDescriptor model = new ModelDescriptor();
        model.setVendorCode(vendorCode);
        model.setModelKey(modelKey);
        return model;
    }

    /** 同一厂商+模型名复用同一实例（{@code shouldCacheInstances}）。 */
    @Test
    void shouldCacheInstances() {
        ModelDescriptor descriptor = model("dashscope", "qwen-max");

        ChatModel first = registry.chat(descriptor);
        ChatModel second = registry.chat(descriptor);
        EmbeddingModel embeddingFirst = registry.embedding(model("dashscope", "text-embedding-v4"));
        EmbeddingModel embeddingSecond = registry.embedding(model("dashscope", "text-embedding-v4"));

        assertSame(first, second);
        assertSame(embeddingFirst, embeddingSecond);
    }

    /** 厂商未配置：按 40461 拒绝（{@code shouldRejectUnknownVendor}）。 */
    @Test
    void shouldRejectUnknownVendor() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> registry.chat(model("no-such-vendor", "m")));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
    }

    /** 模型名为空：按 40001 拒绝（{@code shouldRejectBlankModelKey}）。 */
    @Test
    void shouldRejectBlankModelKey() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> registry.chat(model("dashscope", " ")));

        assertEquals(ErrorCode.MODEL_BAD_REQUEST, error.getErrorCode());
    }

    /** 密钥为空：按厂商未配置拒绝（{@code shouldRejectMissingApiKey}）。 */
    @Test
    void shouldRejectMissingApiKey() {
        vendorProperties.getVendors().get("dashscope").setApiKey(" ");

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> registry.embedding(model("dashscope", "text-embedding-v4")));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
    }
}
