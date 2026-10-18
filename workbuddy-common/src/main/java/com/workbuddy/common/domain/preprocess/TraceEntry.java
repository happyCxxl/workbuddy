package com.workbuddy.common.domain.preprocess;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 处理轨迹条目：单次规则命中的摘要（前后文本为摘要级，防膨胀）。
 *
 * @author cxxl
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TraceEntry {

    /** 动作：仅打标记 */
    public static final String ACTION_MARK = "MARK";

    /** 动作：改写文本（或字段提取，before/after 为字段值） */
    public static final String ACTION_REPLACE = "REPLACE";

    /** 动作：剔除出检索文本内容流 */
    public static final String ACTION_EXCLUDE = "EXCLUDE";

    /** 动作：保持原样（无需改写） */
    public static final String ACTION_KEEP = "KEEP";

    /** 动作：提取结构化字段（原文不变） */
    public static final String ACTION_EXTRACT = "EXTRACT";

    /** 动作：拿不准，不改写 + 人工复核标记 */
    public static final String ACTION_MANUAL_REVIEW = "MANUAL_REVIEW";

    /** 命中规则名（如 header-footer-dispose-v1） */
    private String rule;

    /** 字段类型（PreprocessFieldType 枚举名；可空） */
    private String field;

    /** 动作（MARK/REPLACE/EXCLUDE/KEEP/EXTRACT/MANUAL_REVIEW，取值见本类常量） */
    private String action;

    /** 处理前摘要（可空） */
    private String before;

    /** 处理后摘要（可空） */
    private String after;

    /** 命中证据（判定依据描述） */
    private String evidence;

    public static TraceEntry of(String rule, String field, String action, String before, String after,
                                String evidence) {
        return new TraceEntry(rule, field, action, before, after, evidence);
    }
}
