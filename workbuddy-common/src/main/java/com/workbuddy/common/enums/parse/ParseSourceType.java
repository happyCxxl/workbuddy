package com.workbuddy.common.enums.parse;

/**
 * 解析路类型（字典）：ParseSource.source 的合法取值与来源优先级依据。
 * 落库/落产物形式：value()（小写）。
 */
public enum ParseSourceType {

    /** 原生解析路（唯一产出路） */
    NATIVE("native"),

    /** 结构化 OCR 路（能力接入后新增） */
    OCR("ocr"),

    /** 版面分析路（能力接入后新增） */
    LAYOUT("layout"),

    /** 表格结构识别路（能力接入后新增） */
    TABLE("table");

    private final String value;

    ParseSourceType(String value) {
        this.value = value;
    }

    /** 落库/落产物取值 */
    public String value() {
        return value;
    }

    /** 按落库值反查（未知值返回 null） */
    public static ParseSourceType ofValue(String value) {
        for (ParseSourceType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
