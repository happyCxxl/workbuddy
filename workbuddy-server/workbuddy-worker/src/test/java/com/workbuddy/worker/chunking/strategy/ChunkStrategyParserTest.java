package com.workbuddy.worker.chunking.strategy;

import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import com.workbuddy.common.enums.chunk.PipelineKey;

import org.junit.jupiter.api.Test;

import com.workbuddy.worker.testkit.PropertiesFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片策略解析器单测：结构化解析补全默认 / 空与非法快照抛异常 / pipeline 单键配置映射 / parseConfig 语义。
 */
class ChunkStrategyParserTest {

    private final ChunkStrategyParser parser = new ChunkStrategyParser(PropertiesFixtures.chunk());

    /**
     * 守护 {@code structuredConfigShouldFillMissingDefaults}：该场景行为改动即失败。
     */
    @Test
    void structuredConfigShouldFillMissingDefaults() {
        ChunkStrategy strategy = parser.parse("{\"routes\":{\"body\":{\"algorithm\":\"title-boundary\"}}}");

        assertEquals(ChunkAlgorithm.BODY_TITLE_BOUNDARY, strategy.routeAlgorithm(ChunkRoute.BODY));
        // 其余路由补默认算法 + 参数
        assertEquals(ChunkAlgorithm.TABLE_ROW_SLICE, strategy.routeAlgorithm(ChunkRoute.TABLE));
        assertEquals(ChunkAlgorithm.IMAGE_CAPTION_PLACEHOLDER, strategy.routeAlgorithm(ChunkRoute.IMAGE));
        assertEquals(ChunkAlgorithm.FALLBACK_RECURSIVE, strategy.routeAlgorithm(ChunkRoute.FALLBACK));
        // 所选算法的默认参数被补齐（title-boundary → maxLen=3000；row-slice → groupThreshold=30）
        assertEquals("3000", strategy.route(ChunkRoute.BODY).strParam("maxLen", ""));
        assertEquals(30, strategy.route(ChunkRoute.TABLE).intParam("groupThreshold", -1));
        // 流程层默认
        assertEquals("ON", strategy.pipelineValue(PipelineKey.PARENT_CHILD, ""));
        assertEquals("ON", strategy.pipelineValue(PipelineKey.TITLE_IN_CONTENT, ""));
        assertEquals("OFF", strategy.pipelineValue(PipelineKey.TABLE_IN_BODY_FLOW, ""));
        assertEquals("300", strategy.pipelineValue(PipelineKey.MIN_MERGE_LEN, ""));
    }

    /**
     * 守护 {@code seedSnapshotWithAllRoutesShouldApplyEveryRoute}：该场景行为改动即失败。
     */
    @Test
    void seedSnapshotWithAllRoutesShouldApplyEveryRoute() {
        String seed = "{\"routes\":{"
                + "\"body\":{\"algorithm\":\"fixed-window\",\"params\":{\"len\":\"500\",\"overlap\":\"100\"}},"
                + "\"table\":{\"algorithm\":\"context-merged\","
                + "\"params\":{\"leadMaxLen\":\"200\",\"groupThreshold\":\"30\",\"groupSize\":\"3\"}},"
                + "\"image\":{\"algorithm\":\"caption-placeholder\"},"
                + "\"fallback\":{\"algorithm\":\"fixed-window\",\"params\":{\"len\":\"500\",\"overlap\":\"100\"}}"
                + "},"
                + "\"pipeline\":{\"titlePathMaxLevel\":\"3\",\"parentChild\":\"ON\",\"tableInBodyFlow\":\"OFF\","
                + "\"minMergeLen\":\"200\",\"structureOverlap\":\"0\",\"titleInContent\":\"ON\"}"
                + "}";

        ChunkStrategy strategy = parser.parse(seed);

        assertEquals(ChunkAlgorithm.BODY_FIXED_WINDOW, strategy.routeAlgorithm(ChunkRoute.BODY));
        assertEquals(500, strategy.route(ChunkRoute.BODY).intParam("len", -1));
        assertEquals(100, strategy.route(ChunkRoute.BODY).intParam("overlap", -1));
        assertEquals(ChunkAlgorithm.TABLE_CONTEXT_MERGED, strategy.routeAlgorithm(ChunkRoute.TABLE));
        assertEquals(200, strategy.route(ChunkRoute.TABLE).intParam("leadMaxLen", -1));
        assertEquals(ChunkAlgorithm.IMAGE_CAPTION_PLACEHOLDER, strategy.routeAlgorithm(ChunkRoute.IMAGE));
        assertEquals(ChunkAlgorithm.FALLBACK_FIXED_WINDOW, strategy.routeAlgorithm(ChunkRoute.FALLBACK));
        assertEquals("200", strategy.pipelineValue(PipelineKey.MIN_MERGE_LEN, ""));
        assertEquals("ON", strategy.pipelineValue(PipelineKey.TITLE_IN_CONTENT, ""));
        assertEquals("OFF", strategy.pipelineValue(PipelineKey.TABLE_IN_BODY_FLOW, ""));
    }

    /**
     * 守护 {@code emptyObjectSnapshotShouldStillFillDefaults}：该场景行为改动即失败。
     */
    @Test
    void emptyObjectSnapshotShouldStillFillDefaults() {
        // 只有"空串/JSON 非法"算数据问题：合法 JSON 缺键由补全默认参数覆盖
        ChunkStrategy strategy = parser.parse("{}");

        assertNotNull(strategy);
        assertEquals(ChunkAlgorithm.BODY_PARAGRAPH_AGGREGATE, strategy.routeAlgorithm(ChunkRoute.BODY));
        assertEquals("ON", strategy.pipelineValue(PipelineKey.PARENT_CHILD, ""));
    }

    /**
     * 守护 {@code blankSnapshotShouldThrow}：该场景行为改动即失败。
     */
    @Test
    void blankSnapshotShouldThrow() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> parser.parse(null));

        assertTrue(e.getMessage().contains("切片策略快照为空或已损坏，无法解析"), e.getMessage());
    }

    /**
     * 守护 {@code invalidSnapshotShouldThrowWithTruncatedSnapshot}：该场景行为改动即失败。
     */
    @Test
    void invalidSnapshotShouldThrowWithTruncatedSnapshot() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> parser.parse("not-a-json"));

        assertTrue(e.getMessage().contains("切片策略快照为空或已损坏，无法解析"), e.getMessage());
        assertTrue(e.getMessage().contains("not-a-json"), e.getMessage());
        assertNotNull(e.getCause());
    }

    /**
     * 守护 {@code pipelineOnlyConfigShouldMapParentChild}：该场景行为改动即失败。
     */
    @Test
    void pipelineOnlyConfigShouldMapParentChild() {
        ChunkStrategy strategy = parser.parse("{\"pipeline\":{\"parentChild\":\"OFF\"}}");

        assertEquals("OFF", strategy.pipelineValue(PipelineKey.PARENT_CHILD, ""));
        assertEquals(ChunkAlgorithm.BODY_PARAGRAPH_AGGREGATE, strategy.routeAlgorithm(ChunkRoute.BODY));
    }

    /**
     * 守护 {@code parseConfigShouldLeaveNameVersionNull}：该场景行为改动即失败。
     */
    @Test
    void parseConfigShouldLeaveNameVersionNull() {
        ChunkStrategy strategy = parser.parseConfig("{\"routes\":{}}");

        assertNull(strategy.getName());
        assertNull(strategy.getVersion());
        assertEquals(ChunkAlgorithm.BODY_PARAGRAPH_AGGREGATE, strategy.routeAlgorithm(ChunkRoute.BODY));
    }

    /**
     * 守护 {@code parseConfigShouldThrowOnCorruptSnapshot}：该场景行为改动即失败。
     */
    @Test
    void parseConfigShouldThrowOnCorruptSnapshot() {
        // parseConfig 与 parse 同一口径：坏快照抛异常，不再返回默认策略
        assertThrows(IllegalStateException.class, () -> parser.parseConfig("{oops"));
        assertThrows(IllegalStateException.class, () -> parser.parseConfig(null));
    }
}
