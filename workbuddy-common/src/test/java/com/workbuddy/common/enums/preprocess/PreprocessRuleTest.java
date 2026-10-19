package com.workbuddy.common.enums.preprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预处理规则目录单测。
 *
 * <p>守护 {@code of} 的键解析与规则形态标记：规则目录是配置校验与旧开关换算的依据。
 */
class PreprocessRuleTest {

    /**
     * 每条规则都能按自己的键解析回来，且说明非空。
     *
     * <p>守护 {@code of} 与 {@code desc}：键改错或说明缺失即失败。
     */
    @Test
    void ofShouldResolveEveryKey() {
        for (PreprocessRule rule : PreprocessRule.values()) {
            assertEquals(rule, PreprocessRule.of(rule.key()), () -> rule + " 按自身键解析失败");
            assertFalse(rule.desc().isBlank(), () -> rule + " 缺规则说明");
        }
    }

    /**
     * 键未知时返回 null。
     *
     * <p>守护 {@code of} 的未命中口径。
     */
    @Test
    void ofShouldReturnNullForUnknownKey() {
        assertNull(PreprocessRule.of("no-such-rule"));
    }

    /**
     * 处置规则是三态（KEEP/MARK/EXCLUDE），开关规则不是。
     *
     * <p>守护 {@code triState}：把开关规则标成三态即失败（配置面板渲染错口径）。
     */
    @Test
    void triStateShouldMarkDisposeRulesOnly() {
        assertTrue(PreprocessRule.HEADER_FOOTER.triState());
        assertTrue(PreprocessRule.TOC.triState());
        assertTrue(PreprocessRule.NOISE.triState());
        assertTrue(PreprocessRule.REPEAT.triState());
        assertFalse(PreprocessRule.FIELD.triState());
        assertFalse(PreprocessRule.TIDY.triState());
        assertFalse(PreprocessRule.ENCODING.triState());
    }

    /**
     * 旧开关换算只适用于历史开关规则（repeat），新规则不参与。
     *
     * <p>守护 {@code legacyToggle}：把新规则标成旧开关即失败（存量配置换算串位）。
     */
    @Test
    void legacyToggleShouldMarkRepeatOnly() {
        assertTrue(PreprocessRule.REPEAT.legacyToggle());
        for (PreprocessRule rule : PreprocessRule.values()) {
            if (rule != PreprocessRule.REPEAT) {
                assertFalse(rule.legacyToggle(), () -> rule + " 不应标记为旧开关规则");
            }
        }
    }
}
