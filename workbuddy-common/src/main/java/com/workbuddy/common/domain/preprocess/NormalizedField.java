package com.workbuddy.common.domain.preprocess;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标准化字段：金额/日期/面积/证书号的结构化形式（精确过滤/精确匹配用）。
 *
 * @author cxxl
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NormalizedField {

    /** 字段类型（PreprocessFieldType 枚举名） */
    private String field;

    /** 标准化值（如 "3005000.00" / "2026-08-25" / "10" / "123456"） */
    private String value;

    /** 单位（金额=元、面积=平方米等；可空） */
    private String unit;

    /** 命中规则名（如 amount-cn-v1） */
    private String rule;

    public static NormalizedField of(String field, String value, String unit, String rule) {
        return new NormalizedField(field, value, unit, rule);
    }
}
