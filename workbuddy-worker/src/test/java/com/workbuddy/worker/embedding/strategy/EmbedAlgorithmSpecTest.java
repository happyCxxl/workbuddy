package com.workbuddy.worker.embedding.strategy;

import com.workbuddy.worker.testsupport.catalog.StaticModelCatalog;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量化策略规格单测：保存校验（模型/模板/范围/枚举/未知键透传）+ 保存时目录冗余 enrich。
 *
 * @author cxxl
 */
class EmbedAlgorithmSpecTest {

    private final StaticModelCatalog catalog = new StaticModelCatalog();

    private Map<String, Object> validConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("model", "text-embedding-v4");
        config.put("docTemplate", "{content}");
        config.put("queryTemplate", "{query}");
        config.put("batchSize", 32);
        config.put("timeoutMs", 30000);
        config.put("maxRetries", 2);
        config.put("cacheEnabled", "ON");
        config.put("includeParent", "OFF");
        config.put("skipEmpty", "ON");
        return config;
    }

    @Test
    void validConfigShouldPass() {
        assertNull(EmbedAlgorithmSpec.validate(validConfig(), catalog));
    }

    @Test
    void blankModelShouldFail() {
        Map<String, Object> config = validConfig();
        config.put("model", "");
        assertEquals("模型不能为空", EmbedAlgorithmSpec.validate(config, catalog));
    }

    @Test
    void unknownModelShouldFail() {
        Map<String, Object> config = validConfig();
        config.put("model", "no-such-model");
        assertEquals("模型不存在: no-such-model", EmbedAlgorithmSpec.validate(config, catalog));
    }

    @Test
    void disabledModelShouldFail() {
        Map<String, Object> config = validConfig();
        config.put("model", "text-embedding-v3");
        assertEquals("模型未启用: text-embedding-v3", EmbedAlgorithmSpec.validate(config, catalog));
    }

    @Test
    void templateMissingRequiredPlaceholderShouldFail() {
        Map<String, Object> config = validConfig();
        config.put("docTemplate", "{titlePath}");
        assertEquals("docTemplate 必须包含 {content} 占位符", EmbedAlgorithmSpec.validate(config, catalog));
    }

    @Test
    void templateUnknownPlaceholderShouldFail() {
        Map<String, Object> config = validConfig();
        config.put("queryTemplate", "{query} {answer}");
        assertEquals("queryTemplate 占位符不在白名单: {answer}", EmbedAlgorithmSpec.validate(config, catalog));
    }

    @Test
    void rangesShouldBeEnforced() {
        Map<String, Object> config = validConfig();
        config.put("batchSize", 0);
        assertEquals("batchSize 越界（允许 1~128）", EmbedAlgorithmSpec.validate(config, catalog));
        config.put("batchSize", 129);
        assertEquals("batchSize 越界（允许 1~128）", EmbedAlgorithmSpec.validate(config, catalog));
        config.put("batchSize", 32);
        config.put("timeoutMs", 500);
        assertEquals("timeoutMs 越界（允许 1000~120000）", EmbedAlgorithmSpec.validate(config, catalog));
        config.put("timeoutMs", 30000);
        config.put("maxRetries", 6);
        assertEquals("maxRetries 越界（允许 0~5）", EmbedAlgorithmSpec.validate(config, catalog));
    }

    @Test
    void enumValuesShouldBeEnforced() {
        Map<String, Object> config = validConfig();
        config.put("cacheEnabled", "YES");
        assertEquals("cacheEnabled 只能是 ON/OFF", EmbedAlgorithmSpec.validate(config, catalog));
        config.put("cacheEnabled", "off");
        assertNull(EmbedAlgorithmSpec.validate(config, catalog));
    }

    @Test
    void unknownKeysShouldPassThrough() {
        Map<String, Object> config = validConfig();
        config.put("futureOption", "x");
        assertNull(EmbedAlgorithmSpec.validate(config, catalog));
    }

    @Test
    void enrichShouldStampCatalogAndKeepInputUntouched() {
        Map<String, Object> config = validConfig();

        Map<String, Object> stamped = EmbedAlgorithmSpec.enrich(config, catalog);

        assertEquals(1024, stamped.get("dimension"));
        assertEquals("COSINE", stamped.get("metric"));
        assertEquals(Boolean.TRUE, stamped.get("normalized"));
        assertEquals(8192, stamped.get("contextWindowTokens"));
        assertEquals(64, stamped.get("batchLimit"));
        assertNull(config.get("dimension"), "enrich 不得修改入参");
        assertTrue(stamped.containsKey("model"));
    }

    @Test
    void enrichInvalidConfigShouldReturnNull() {
        Map<String, Object> config = validConfig();
        config.put("model", "no-such-model");
        assertNull(EmbedAlgorithmSpec.enrich(config, catalog));
    }
}
