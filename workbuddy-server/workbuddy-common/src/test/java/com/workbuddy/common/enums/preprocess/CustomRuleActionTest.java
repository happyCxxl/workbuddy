package com.workbuddy.common.enums.preprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自定义规则动作单测。
 *
 * <p>守护 {@code of} 的动作名解析与「是否需要替换文本」标记：动作目录是注册校验的依据。
 */
class CustomRuleActionTest {

    /**
     * 每个动作都能按名字解析回来。
     *
     * <p>守护 {@code of}：动作名改错即失败（保存的自定义规则解析不了）。
     */
    @Test
    void ofShouldResolveEveryName() {
        for (CustomRuleAction action : CustomRuleAction.values()) {
            assertEquals(action, CustomRuleAction.of(action.name()), () -> action + " 按名字解析失败");
        }
        assertNull(CustomRuleAction.of("no-such-action"));
    }

    /**
     * 动作名清单与常量一一对应（前端下拉的选项来源）。
     *
     * <p>守护 {@code names}：漏一个动作即失败（前端少一个可选项）。
     */
    @Test
    void namesShouldListAllActions() {
        String joined = CustomRuleAction.names();
        for (CustomRuleAction action : CustomRuleAction.values()) {
            assertTrue(joined.contains(action.name()), () -> action + " 未列入动作清单");
        }
    }

    /**
     * 只有替换动作需要替换文本，移除与抽取不需要。
     *
     * <p>守护 {@code needsReplacement}：把 REPLACE 标成免填即失败（注册校验漏拦截空替换文本）。
     */
    @Test
    void onlyReplaceShouldNeedReplacementText() {
        assertTrue(CustomRuleAction.REPLACE.needsReplacement());
        assertFalse(CustomRuleAction.REMOVE.needsReplacement());
        assertFalse(CustomRuleAction.EXTRACT.needsReplacement());
    }

    /**
     * 每个动作有说明。
     *
     * <p>守护 {@code desc}：说明缺失即失败（前端提示缺文案）。
     */
    @Test
    void everyActionShouldCarryDescription() {
        for (CustomRuleAction action : CustomRuleAction.values()) {
            assertFalse(action.desc().isBlank(), () -> action + " 缺动作说明");
        }
    }
}
