package com.workbuddy.worker.preprocessing.rule;

import com.workbuddy.common.enums.preprocess.CustomRuleAction;

import java.util.regex.Pattern;

/**
 * 编译后的自定义规则：管线在 buildRuleContext 时对每条用户正则预编译一次，
 * 规则执行期直接复用（避免每元素重复编译）。
 *
 * @param pattern       编译后的正则
 * @param action        动作（REMOVE/REPLACE/EXTRACT）
 * @param replacement   替换文本（仅 REPLACE；支持 $1 组引用）
 * @param sourcePattern 原始正则（trace 展示用）
 */
public record CustomRuleMatcher(Pattern pattern, CustomRuleAction action, String replacement, String sourcePattern) {
}
