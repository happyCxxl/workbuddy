package com.workbuddy.common.dto.response.preprocess;

import lombok.Data;

/**
 * 处理轨迹条目 VO：TraceEntry 的对外视图（规则 / 字段 / 动作 / 前后摘要 / 判定证据）。
 */
@Data
public class PreprocessTraceVO {

    /** 命中规则名（如 header-footer-dispose-v1） */
    private String rule;

    /** 字段类型（PreprocessFieldType 枚举名；可空） */
    private String field;

    /** 动作（MARK/REPLACE/EXCLUDE/EXTRACT/MANUAL_REVIEW） */
    private String action;

    /** 处理前摘要（可空） */
    private String before;

    /** 处理后摘要（可空） */
    private String after;

    /** 命中证据（判定依据描述） */
    private String evidence;
}
