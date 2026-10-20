package com.workbuddy.worker.chunking.strategy;

import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.common.enums.chunk.ChunkParam;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.worker.chunking.ChunkProperties;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切片算法规格单测：保存校验口径（支持集/参数范围/不变量/互斥/非法结构）+ 支持集判定 + 默认参数补齐。
 */
class ChunkAlgorithmSpecTest {

    private Map<String, Object> config(String json) {
        return JsonUtil.toMap(json);
    }

    /**
     * 守护 {@code unsupportedAlgorithmShouldBeRejected}：该场景行为改动即失败。
     */
    @Test
    void unsupportedAlgorithmShouldBeRejected() {
        String error = ChunkAlgorithmSpec.validate(config("{\"routes\":{\"body\":{\"algorithm\":\"semantic\"}}}"));

        assertNotNull(error);
        assertTrue(error.contains("semantic"));
    }

    /**
     * 守护 {@code paramOutOfRangeShouldBeRejected}：该场景行为改动即失败。
     */
    @Test
    void paramOutOfRangeShouldBeRejected() {
        String error = ChunkAlgorithmSpec.validate(config(
                "{\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\","
                        + "\"params\":{\"targetMaxLen\":\"99999\"}}}}"));

        assertNotNull(error);
        assertTrue(error.contains("参数越界"));
    }

    /**
     * 守护 {@code softBelowTargetShouldBeRejected}：该场景行为改动即失败。
     */
    @Test
    void softBelowTargetShouldBeRejected() {
        String error = ChunkAlgorithmSpec.validate(config(
                "{\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\","
                        + "\"params\":{\"targetMaxLen\":\"800\",\"softMaxLen\":\"500\"}}}}"));

        assertNotNull(error);
        assertTrue(error.contains("softMaxLen"));
    }

    /**
     * 守护 {@code contextMergedWithTableInBodyFlowShouldBeRejected}：该场景行为改动即失败。
     */
    @Test
    void contextMergedWithTableInBodyFlowShouldBeRejected() {
        String error = ChunkAlgorithmSpec.validate(config(
                "{\"routes\":{\"table\":{\"algorithm\":\"context-merged\"}},"
                        + "\"pipeline\":{\"tableInBodyFlow\":\"ON\"}}"));

        assertNotNull(error);
        assertTrue(error.contains("同时启用"));
    }

    /**
     * 守护 {@code invalidStructureShouldBeRejected}：该场景行为改动即失败。
     */
    @Test
    void invalidStructureShouldBeRejected() {
        String error = ChunkAlgorithmSpec.validate(config("{\"routes\":\"not-a-map\"}"));

        assertNotNull(error);
        assertTrue(error.contains("routes"));
    }

    /**
     * 守护 {@code validConfigShouldPass}：该场景行为改动即失败。
     */
    @Test
    void validConfigShouldPass() {
        String error = ChunkAlgorithmSpec.validate(config(
                "{\"routes\":{\"body\":{\"algorithm\":\"title-boundary\","
                        + "\"params\":{\"maxLen\":\"3000\"}}},"
                        + "\"pipeline\":{\"titleInContent\":\"ON\"}}"));

        assertNull(error);
    }

    /**
     * 守护 {@code supportedSetShouldFollowCatalog}：该场景行为改动即失败。
     */
    @Test
    void supportedSetShouldFollowCatalog() {
        assertTrue(ChunkAlgorithmSpec.isSupported(ChunkRoute.BODY, "paragraph-aggregate"));
        assertFalse(ChunkAlgorithmSpec.isSupported(ChunkRoute.BODY, "semantic"));
        assertFalse(ChunkAlgorithmSpec.isSupported(ChunkRoute.BODY, "unknown-algorithm"));
    }

    /**
     * 守护 {@code defaultParamsShouldFillPerAlgorithm}：该场景行为改动即失败。
     */
    @Test
    void defaultParamsShouldFillPerAlgorithm() {
        Map<String, String> defaults = ChunkAlgorithmSpec.defaultParams(ChunkRoute.TABLE, "row-group",
                new ChunkProperties());

        assertEquals("5", defaults.get("groupSize"));
        assertEquals("600", defaults.get("maxLen"));
    }

    /**
     * 守护 {@code defaultParamsShouldAllExistInCatalogue}：该场景行为改动即失败。
     */
    @Test
    void defaultParamsShouldAllExistInCatalogue() {
        for (ChunkAlgorithm algorithm : ChunkAlgorithm.values()) {
            Set<String> allowed = new HashSet<>();
            ChunkParam.ofAlgorithm(algorithm).forEach(param -> allowed.add(param.key()));
            Set<String> defaults = ChunkAlgorithmSpec
                    .defaultParams(algorithm.route(), algorithm.key(), new ChunkProperties()).keySet();
            for (String key : defaults) {
                assertTrue(allowed.contains(key),
                        algorithm.key() + " 的默认参数 " + key + " 不在 ChunkParam 目录里");
            }
        }
    }

    /**
     * 守护 {@code catalogueShouldCoverEveryParamKeyConstant}：该场景行为改动即失败。
     */
    @Test
    void catalogueShouldCoverEveryParamKeyConstant() {
        Set<String> keys = new HashSet<>();
        for (ChunkParam param : ChunkParam.values()) {
            keys.add(param.key());
        }
        for (String constant : new String[] { ChunkParamKeys.TARGET_MAX_LEN, ChunkParamKeys.SOFT_MAX_LEN,
                ChunkParamKeys.LEN, ChunkParamKeys.OVERLAP, ChunkParamKeys.MAX_LEN, ChunkParamKeys.LEAD_MAX_LEN,
                ChunkParamKeys.GROUP_THRESHOLD, ChunkParamKeys.GROUP_SIZE }) {
            assertTrue(keys.contains(constant), "参数键常量 " + constant + " 在 ChunkParam 目录里没有对应条目");
        }
    }

    /**
     * 守护 {@code rangeBoundaryShouldBeAcceptedAtCatalogueEdges}：该场景行为改动即失败。
     */
    @Test
    void rangeBoundaryShouldBeAcceptedAtCatalogueEdges() {
        assertNull(ChunkAlgorithmSpec.validate(config(
                "{\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\","
                        + "\"params\":{\"targetMaxLen\":\"100\",\"softMaxLen\":\"100\"}}}}")));
        assertNull(ChunkAlgorithmSpec.validate(config(
                "{\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\","
                        + "\"params\":{\"targetMaxLen\":\"5000\",\"softMaxLen\":\"8000\"}}}}")));
        assertNotNull(ChunkAlgorithmSpec.validate(config(
                "{\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\",\"params\":{\"softMaxLen\":\"99\"}}}}")));
        assertNull(ChunkAlgorithmSpec.validate(config(
                "{\"routes\":{\"fallback\":{\"algorithm\":\"recursive-length\","
                        + "\"params\":{\"len\":\"5000\",\"overlap\":\"1000\"}}}}")));
        assertNotNull(ChunkAlgorithmSpec.validate(config(
                "{\"routes\":{\"fallback\":{\"algorithm\":\"recursive-length\","
                        + "\"params\":{\"len\":\"5000\",\"overlap\":\"1001\"}}}}")));
    }

    /**
     * 守护 {@code unknownParamKeyShouldPassThrough}：该场景行为改动即失败。
     */
    @Test
    void unknownParamKeyShouldPassThrough() {
        assertNull(ChunkAlgorithmSpec.validate(config(
                "{\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\","
                        + "\"params\":{\"someFutureKey\":\"abc\"}}}}")));
    }
}
