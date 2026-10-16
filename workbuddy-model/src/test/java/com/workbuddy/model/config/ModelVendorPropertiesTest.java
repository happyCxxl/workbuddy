package com.workbuddy.model.config;

import com.workbuddy.common.enums.model.AuthType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.model.startup.ModelVendorValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 厂商配置单测：键映射绑定、留空字段的默认口径、启动期校验逐条规则与错误码。
 *
 * @author cxxl
 */
class ModelVendorPropertiesTest {

    private final ModelVendorValidator validator = new ModelVendorValidator(new ModelVendorProperties());

    @Test
    void shouldBindMultipleVendorsWithAllFields() {
        ModelVendorProperties properties = bind(Map.of(
                "knowledge.model.vendors.dashscope.base-url", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "knowledge.model.vendors.dashscope.auth-type", "BEARER",
                "knowledge.model.vendors.dashscope.api-key", "sk-dashscope",
                "knowledge.model.vendors.dashscope.timeout-ms", "30000",
                "knowledge.model.vendors.dashscope.max-retries", "2",
                "knowledge.model.vendors.custom.base-url", "https://llm.example.com/v1",
                "knowledge.model.vendors.custom.auth-type", "BEARER",
                "knowledge.model.vendors.custom.api-key", "sk-custom",
                "knowledge.model.vendors.custom.timeout-ms", "45000"));

        assertEquals(2, properties.getVendors().size());
        ModelVendorProperties.Vendor dashscope = properties.getVendors().get("dashscope");
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", dashscope.getBaseUrl());
        assertEquals(AuthType.BEARER, dashscope.getAuthType());
        assertEquals("sk-dashscope", dashscope.getApiKey());
        assertEquals(30000, dashscope.getTimeoutMs());
        assertEquals(2, dashscope.getMaxRetries());

        ModelVendorProperties.Vendor custom = properties.getVendors().get("custom");
        assertEquals("https://llm.example.com/v1", custom.getBaseUrl());
        assertEquals(AuthType.BEARER, custom.getAuthType());
        assertEquals("sk-custom", custom.getApiKey());
        assertEquals(45000, custom.getTimeoutMs());
    }

    @Test
    void shouldKeepTimeoutAndRetriesEmptyWhenAbsent() {
        ModelVendorProperties properties = bind(Map.of(
                "knowledge.model.vendors.dashscope.auth-type", "BEARER",
                "knowledge.model.vendors.dashscope.api-key", "sk-dashscope"));

        ModelVendorProperties.Vendor vendor = properties.getVendors().get("dashscope");
        assertNull(vendor.getTimeoutMs());
        assertNull(vendor.getMaxRetries());
        assertNull(vendor.getBaseUrl());
        assertDoesNotThrow(() -> validator.validate(properties));
    }

    @Test
    void shouldExposeNoVendorsWhenSectionAbsent() {
        ModelVendorProperties properties = bind(Map.of());

        assertTrue(properties.getVendors().isEmpty());
    }

    @Test
    void shouldPassStartupWithEmptyVendors() {
        ModelVendorProperties properties = new ModelVendorProperties();

        assertDoesNotThrow(() -> validator.validate(properties));
    }

    @Test
    void shouldPassStartupWithSingleVendor() {
        assertDoesNotThrow(() -> validator.validate(propertiesOf("dashscope", vendor())));
    }

    @Test
    void shouldPassStartupWithMultipleVendorsConfigured() {
        ModelVendorProperties properties = new ModelVendorProperties();
        Map<String, ModelVendorProperties.Vendor> vendors = new LinkedHashMap<>();
        vendors.put("custom", vendor());
        vendors.put("dashscope", vendor());
        properties.setVendors(vendors);

        assertDoesNotThrow(() -> validator.validate(properties));
    }

    @Test
    void shouldFailStartupWhenApiKeyMissing() {
        ModelVendorProperties.Vendor vendor = vendor();
        vendor.setBaseUrl("https://llm.example.com/v1");
        vendor.setApiKey(null);

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> validator.validate(propertiesOf("dashscope", vendor)));

        assertEquals(ErrorCode.MODEL_AUTH_FAILED, error.getErrorCode());
        assertTrue(error.getMessage().contains("dashscope"), error.getMessage());
        assertTrue(error.getMessage().contains("knowledge.model.vendors.dashscope.api-key"), error.getMessage());
    }

    @Test
    void shouldFailStartupWhenApiKeyBlank() {
        ModelVendorProperties.Vendor vendor = vendor();
        vendor.setApiKey("   ");

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> validator.validate(propertiesOf("dashscope", vendor)));

        assertEquals(ErrorCode.MODEL_AUTH_FAILED, error.getErrorCode());
        assertTrue(error.getMessage().contains("dashscope"), error.getMessage());
    }

    @Test
    void shouldFailStartupWhenAuthTypeAbsent() {
        ModelVendorProperties.Vendor vendor = vendor();
        vendor.setAuthType(null);

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> validator.validate(propertiesOf("dashscope", vendor)));

        assertEquals(ErrorCode.PARAM_INVALID, error.getErrorCode());
        assertTrue(error.getMessage().contains("dashscope"), error.getMessage());
        assertTrue(error.getMessage().contains("auth-type"), error.getMessage());
    }

    @Test
    void shouldPassStartupWhenAuthTypeBearer() {
        ModelVendorProperties.Vendor vendor = vendor();

        assertDoesNotThrow(() -> validator.validate(propertiesOf("dashscope", vendor)));
    }

    @Test
    void shouldFailStartupWhenTimeoutNotPositive() {
        ModelVendorProperties.Vendor zero = vendor();
        zero.setTimeoutMs(0);
        KnowledgeException zeroError = assertThrows(KnowledgeException.class,
                () -> validator.validate(propertiesOf("dashscope", zero)));

        assertEquals(ErrorCode.PARAM_INVALID, zeroError.getErrorCode());
        assertTrue(zeroError.getMessage().contains("dashscope"), zeroError.getMessage());
        assertTrue(zeroError.getMessage().contains("timeout-ms"), zeroError.getMessage());

        ModelVendorProperties.Vendor negative = vendor();
        negative.setTimeoutMs(-1);
        KnowledgeException negativeError = assertThrows(KnowledgeException.class,
                () -> validator.validate(propertiesOf("dashscope", negative)));

        assertEquals(ErrorCode.PARAM_INVALID, negativeError.getErrorCode());
        assertTrue(negativeError.getMessage().contains("dashscope"), negativeError.getMessage());
    }

    @Test
    void shouldFailStartupWhenBaseUrlIllegal() {
        for (String baseUrl : new String[] {"not-a-url", "ftp://llm.example.com/v1", "http://", "https://"}) {
            ModelVendorProperties.Vendor vendor = vendor();
            vendor.setBaseUrl(baseUrl);

            KnowledgeException error = assertThrows(KnowledgeException.class,
                    () -> validator.validate(propertiesOf("dashscope", vendor)), baseUrl);

            assertEquals(ErrorCode.PARAM_INVALID, error.getErrorCode(), baseUrl);
            assertTrue(error.getMessage().contains("dashscope"), error.getMessage());
        }
    }

    @Test
    void shouldFailStartupWhenVendorCodeBlank() {
        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> validator.validate(propertiesOf("  ", vendor())));

        assertEquals(ErrorCode.MODEL_VENDOR_UNKNOWN, error.getErrorCode());
    }

    @Test
    void shouldPassStartupWithFullVendorConfig() {
        ModelVendorProperties.Vendor vendor = vendor();
        vendor.setBaseUrl("https://llm.example.com/v1");
        vendor.setTimeoutMs(60000);
        vendor.setMaxRetries(3);

        assertDoesNotThrow(() -> validator.validate(propertiesOf("dashscope", vendor)));
    }

    /** 字段齐全的厂商：鉴权方式 BEARER、密钥非空 */
    private ModelVendorProperties.Vendor vendor() {
        ModelVendorProperties.Vendor vendor = new ModelVendorProperties.Vendor();
        vendor.setAuthType(AuthType.BEARER);
        vendor.setApiKey("sk-test");
        return vendor;
    }

    /** 单厂商配置容器 */
    private ModelVendorProperties propertiesOf(String vendorCode, ModelVendorProperties.Vendor vendor) {
        ModelVendorProperties properties = new ModelVendorProperties();
        Map<String, ModelVendorProperties.Vendor> vendors = new LinkedHashMap<>();
        vendors.put(vendorCode, vendor);
        properties.setVendors(vendors);
        return properties;
    }

    /**
     * 按配置键映射绑定（kebab-case 键名与 yml 一致）。
     *
     * <p>绑定前缀取 `knowledge.model`：Binder 无法在 map 键所在层建出目标对象，落在
     * `knowledge.model.vendors` 会得到空 map，落到它的父层才与容器绑定同解。
     */
    private ModelVendorProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("knowledge.model", ModelVendorProperties.class)
                .orElseGet(ModelVendorProperties::new);
    }
}
