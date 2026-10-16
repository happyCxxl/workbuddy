package com.workbuddy.worker.embedding.strategy;

import com.workbuddy.common.enums.embed.EmbedMetric;
import com.workbuddy.common.enums.embed.EmbeddingModel;
import com.workbuddy.worker.testsupport.catalog.StaticModelCatalog;
import com.workbuddy.worker.embedding.EmbedProperties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量化策略解析器单测：默认补齐 / 目录冗余回填 / 空与非法快照抛异常 / parseConfig 不设 name/version。
 *
 * @author cxxl
 */
class EmbedStrategyParserTest {

    private final EmbedStrategyParser parser = new EmbedStrategyParser(new EmbedProperties(), new StaticModelCatalog());

    @Test
    void configShouldFillDefaultsAndStampCatalog() {
        EmbedStrategy strategy = parser.parse("{\"model\":\"text-embedding-v4\",\"batchSize\":64,\"cacheEnabled\":\"OFF\"}");

        assertEquals("text-embedding-v4", strategy.getModel());
        assertEquals("{content}", strategy.getDocTemplate());
        assertEquals("{query}", strategy.getQueryTemplate());
        assertEquals(64, strategy.getBatchSize());
        assertEquals(30000, strategy.getTimeoutMs());
        assertEquals(2, strategy.getMaxRetries());
        assertEquals("OFF", strategy.getCacheEnabled());
        assertEquals("ON", strategy.getSkipEmpty());
        assertEquals("OFF", strategy.getIncludeParent());
        // 目录冗余回填
        assertEquals(1024, strategy.getDimension());
        assertEquals(EmbedMetric.COSINE.key(), strategy.getMetric());
        assertTrue(strategy.getNormalized());
        assertEquals(8192, strategy.getContextWindowTokens());
        assertEquals(64, strategy.getBatchLimit());
    }

    @Test
    void blankModelShouldFallbackToFirstEnabledModel() {
        EmbedStrategy strategy = parser.parse("{\"batchSize\":16}");

        assertEquals(EmbeddingModel.firstEnabled().key(), strategy.getModel());
        assertEquals(EmbeddingModel.firstEnabled().dimension(), strategy.getDimension());
        assertEquals(16, strategy.getBatchSize());
    }

    @Test
    void blankSnapshotShouldThrow() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> parser.parse(null));

        assertTrue(e.getMessage().contains("向量化策略快照为空或已损坏，无法解析"), e.getMessage());
    }

    @Test
    void invalidSnapshotShouldThrowWithTruncatedSnapshot() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> parser.parse("not-a-json"));

        assertTrue(e.getMessage().contains("向量化策略快照为空或已损坏，无法解析"), e.getMessage());
        assertTrue(e.getMessage().contains("not-a-json"), e.getMessage());
        assertNotNull(e.getCause());
    }

    @Test
    void disabledModelShouldStillParseAndStampCatalog() {
        // 解析不校验启用（启用校验在保存/触发侧），目录冗余仍按目录回填
        EmbedStrategy strategy = parser.parse("{\"model\":\"text-embedding-v3\"}");

        assertEquals("text-embedding-v3", strategy.getModel());
        assertEquals(1024, strategy.getDimension());
        assertEquals(8192, strategy.getContextWindowTokens());
    }

    @Test
    void parseConfigShouldLeaveNameVersionNull() {
        EmbedStrategy strategy = parser.parseConfig("{\"model\":\"text-embedding-v4\"}");

        assertNull(strategy.getName());
        assertNull(strategy.getVersion());
        assertEquals("text-embedding-v4", strategy.getModel());
    }

    @Test
    void parseConfigShouldThrowOnCorruptSnapshot() {
        // parseConfig 与 parse 同一口径：坏快照抛异常，不再返回默认策略
        assertThrows(IllegalStateException.class, () -> parser.parseConfig("{oops"));
        assertThrows(IllegalStateException.class, () -> parser.parseConfig(""));
    }
}
