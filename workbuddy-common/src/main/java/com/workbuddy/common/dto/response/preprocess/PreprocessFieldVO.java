package com.workbuddy.common.dto.response.preprocess;

import lombok.Data;

/**
 * 标准化字段 VO：NormalizedField 的对外视图（金额/日期/面积/证书号的结构化形式）。
 */
@Data
public class PreprocessFieldVO {

    /** 字段类型（PreprocessFieldType 枚举名） */
    private String field;

    /** 标准化值 */
    private String value;

    /** 单位（金额=元、面积=平方米等；可空） */
    private String unit;

    /** 命中规则名（如 amount-cn-v1） */
    private String rule;
}
