package com.workbuddy.worker.preprocessing.strategy;

import com.workbuddy.common.enums.preprocess.CustomRuleAction;
import com.workbuddy.common.enums.preprocess.PreprocessRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预处理算法规格单测：默认档（三态默认标记、重复默认剔除）与保存校验（含重复旧开关格式的迁移口径）。
 */
class PreprocessAlgorithmSpecTest {

    @Test
    void triStateRulesShouldDefaultToMarkAndRepeatToExclude() {
        assertEquals("MARK", PreprocessAlgorithmSpec.defaultAction(PreprocessRule.HEADER_FOOTER));
        assertEquals("MARK", PreprocessAlgorithmSpec.defaultAction(PreprocessRule.TOC));
        assertEquals("MARK", PreprocessAlgorithmSpec.defaultAction(PreprocessRule.NOISE));
        assertEquals("EXCLUDE", PreprocessAlgorithmSpec.defaultAction(PreprocessRule.REPEAT));
        assertNull(PreprocessAlgorithmSpec.defaultAction(PreprocessRule.TIDY));
    }

    @Test
    void toggleRulesShouldKeepTheirDefaultSwitch() {
        assertTrue(PreprocessAlgorithmSpec.defaultEnabled(PreprocessRule.FIELD));
        assertTrue(PreprocessAlgorithmSpec.defaultEnabled(PreprocessRule.TIDY));
        assertTrue(PreprocessAlgorithmSpec.defaultEnabled(PreprocessRule.ENCODING));
        assertFalse(PreprocessAlgorithmSpec.defaultEnabled(PreprocessRule.REPEAT));
    }

    @Test
    void repeatActionShouldPassValidation() {
        Map<String, Object> config = Map.of("rules", Map.of("repeat", Map.of("action", "MARK")));

        assertNull(PreprocessAlgorithmSpec.validate(config));
    }

    @Test
    void legacyRepeatToggleShouldPassValidation() {
        Map<String, Object> config = Map.of("rules", Map.of("repeat", Map.of("enabled", "OFF")));

        assertNull(PreprocessAlgorithmSpec.validate(config));
    }

    @Test
    void repeatWithBothActionAndEnabledShouldBeRejected() {
        Map<String, Object> config = Map.of("rules", Map.of("repeat", Map.of("action", "MARK", "enabled", "ON")));

        String error = PreprocessAlgorithmSpec.validate(config);

        assertNotNull(error);
        assertTrue(error.contains("只保留 action"));
    }

    @Test
    void toggleRuleWithActionShouldStillBeRejected() {
        Map<String, Object> config = Map.of("rules", Map.of("tidy", Map.of("action", "MARK")));

        assertNotNull(PreprocessAlgorithmSpec.validate(config));
    }

    @Test
    void otherTriStateRuleWithEnabledShouldStillBeRejected() {
        Map<String, Object> config = Map.of("rules", Map.of("toc", Map.of("enabled", "ON")));

        assertNotNull(PreprocessAlgorithmSpec.validate(config));
    }

    @Test
    void customGroupUnderRulesShouldBeRejected() {
        Map<String, Object> config = Map.of("rules", Map.of("custom", Map.of("enabled", "ON")));

        String error = PreprocessAlgorithmSpec.validate(config);

        assertNotNull(error);
        assertTrue(error.contains("custom"));
    }

    @Test
    void customActionCatalogShouldDriveReplacementConstraint() {
        assertEquals("REMOVE/REPLACE/EXTRACT", CustomRuleAction.names());
        assertTrue(CustomRuleAction.REPLACE.needsReplacement());
        assertFalse(CustomRuleAction.REMOVE.needsReplacement());
        assertFalse(CustomRuleAction.EXTRACT.needsReplacement());
        assertTrue(CustomRuleAction.EXTRACT.desc().contains("命中"));
    }

    @Test
    void customRuleWithReplacementOnExtractShouldBeRejected() {
        Map<String, Object> config = Map.of("custom", Map.of("enabled", "ON", "rules",
                List.of(Map.of("pattern", "x+", "action", "EXTRACT", "replacement", "y"))));

        String error = PreprocessAlgorithmSpec.validate(config);

        assertNotNull(error);
        assertTrue(error.contains("不允许携带替换文本"));
    }

    @Test
    void customRemoveRuleWithoutReplacementShouldPass() {
        Map<String, Object> config = Map.of("custom", Map.of("enabled", "ON", "rules",
                List.of(Map.of("pattern", "x+", "action", "REMOVE"))));

        assertNull(PreprocessAlgorithmSpec.validate(config));
    }
}
