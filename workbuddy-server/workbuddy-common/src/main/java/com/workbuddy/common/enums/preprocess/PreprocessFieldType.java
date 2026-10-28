package com.workbuddy.common.enums.preprocess;

/**
 * 标准化字段类型（招投标字段规范化 11.4 规则表）。
 */
public enum PreprocessFieldType {

    /** 金额 */
    AMOUNT,

    /** 日期 */
    DATE,

    /** 面积（数值 + 单位平方米） */
    AREA,

    /** 证书号/编号 */
    CERT_NO
}
