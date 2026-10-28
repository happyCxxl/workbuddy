package com.workbuddy.worker.indexing;

import com.workbuddy.common.enums.index.IndexShape;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组合快照格式断言单测：
 * stageStrategies 三环节策略维度是组合的身份与血缘载体，缺少该维度的快照
 * 由唯一断言点 requireStageStrategies 拒绝（40448）。
 */
class ComboSnapshotTest {

    /**
     * 守护 {@code hasCompleteStageStrategiesShouldBeTrueForFactoryCombo}：该场景行为改动即失败。
     */
    @Test
    void hasCompleteStageStrategiesShouldBeTrueForFactoryCombo() {
        assertTrue(ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1")
                .hasCompleteStageStrategies());
    }

    /**
     * 守护 {@code hasCompleteStageStrategiesShouldBeFalseForLegacyShapedSnapshot}：该场景行为改动即失败。
     */
    @Test
    void hasCompleteStageStrategiesShouldBeFalseForLegacyShapedSnapshot() {
        assertFalse(new ComboSnapshot().hasCompleteStageStrategies());

        ComboSnapshot nullMap = new ComboSnapshot();
        nullMap.setStageStrategies(null);
        assertFalse(nullMap.hasCompleteStageStrategies());
    }

    /**
     * 守护 {@code hasCompleteStageStrategiesShouldBeFalseWhenAnyStageMissing}：该场景行为改动即失败。
     */
    @Test
    void hasCompleteStageStrategiesShouldBeFalseWhenAnyStageMissing() {
        assertFalse(ComboSnapshot.of(null, "chunk-hybrid-v1", "embed-default-v1").hasCompleteStageStrategies());
        assertFalse(ComboSnapshot.of("preproc-default-v1", "", "embed-default-v1").hasCompleteStageStrategies());
        assertFalse(ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", null).hasCompleteStageStrategies());
    }

    /**
     * 守护 {@code requireStageStrategiesShouldThrowExplicitErrorForLegacySnapshot}：该场景行为改动即失败。
     */
    @Test
    void requireStageStrategiesShouldThrowExplicitErrorForLegacySnapshot() {
        WorkbuddyException ex = assertThrows(WorkbuddyException.class,
                () -> new ComboSnapshot().requireStageStrategies());

        assertEquals(ErrorCode.INDEX_COMBO_SNAPSHOT_LEGACY.getCode(), ex.getCode());
    }

    /**
     * 守护 {@code requireStageStrategiesShouldPassForCompleteCombo}：该场景行为改动即失败。
     */
    @Test
    void requireStageStrategiesShouldPassForCompleteCombo() {
        assertDoesNotThrow(() -> ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1")
                .requireStageStrategies());
    }

    /**
     * 守护 {@code isListScopeShouldReflectScopeMode}：该场景行为改动即失败。
     */
    @Test
    void isListScopeShouldReflectScopeMode() {
        ComboSnapshot combo = new ComboSnapshot();
        assertFalse(combo.isListScope());

        combo.setFileScopeMode("LIST");
        assertTrue(combo.isListScope());
    }

    /**
     * 守护 {@code shapeShouldRoundTripAsEnumName}：该场景行为改动即失败。
     */
    @Test
    void shapeShouldRoundTripAsEnumName() {
        String json = Objects.requireNonNull(
                JsonUtil.toJsonStr(ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1")));

        assertTrue(json.contains("\"FULL_VECTOR\""), json);
        ComboSnapshot parsed = Objects.requireNonNull(JsonUtil.toObject(json, ComboSnapshot.class));
        assertEquals(IndexShape.FULL_VECTOR, parsed.getShape());
    }
}
