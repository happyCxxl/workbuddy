package com.workbuddy.common.enums.structure;

/**
 * 统一元素扩展区键名（字典）：UnifiedElement.extension 的合法键，
 * 解析环节写入、组装/下游环节读取的跨环节契约。
 * 落库/落产物形式：key()。
 */
public enum ElementExtensionKey {

    /** 来源路名（ParseSourceType.value()） */
    SOURCE("source"),

    /** 来源解析器/提供方（如 pdfbox-3.0.4、poi-5.4.0） */
    PROVIDER("provider"),

    /** 原生样式名（Office 解析器写入） */
    STYLE("style"),

    /** sheet 名（Excel 解析器写入） */
    SHEET_NAME("sheetName"),

    /** 需要 OCR 识别的图片引用标记 */
    NEEDS_OCR("needsOcr"),

    /** 表格被页底切断标记（PDF 解析器写入，跨页接续判定用） */
    CUT_AT_PAGE_BOTTOM("cutAtPageBottom"),

    /** 页眉重复出现标记（预留未实现：当前无解析器写入、无读取方） */
    HEADER_REPEATED("headerRepeated");

    private final String key;

    ElementExtensionKey(String key) {
        this.key = key;
    }

    /** 落库/落产物取值。 */
    public String key() {
        return key;
    }
}
