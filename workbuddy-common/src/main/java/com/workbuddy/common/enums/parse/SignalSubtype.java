package com.workbuddy.common.enums.parse;

/**
 * 信号子类型：信号判定的结构化结论，是管线降级分支的控制流依据。
 * evidence 仅作展示，分支判定一律按本枚举（不依赖文案）。
 */
public enum SignalSubtype {

    /** 扫描页：整页无文本但图片覆盖高（OCR 预留，计失败单元） */
    SCANNED,

    /** 空白页：整页无文本且无图片覆盖（不计失败单元） */
    BLANK,

    /** 纯矢量页：整页无文本无图片但有矢量图形覆盖（图表/示意图，不计失败单元） */
    VECTOR_GRAPHIC,

    /** 乱码页：不可读码点占比超阈值 */
    GARBLED,

    /** 乱码页（CID 映射失败）：字体缺 ToUnicode 映射，提取文本按映射失败处置 */
    CID_MAPPING_FAILED,

    /** 文字极少或文字占比低：疑似图片页（仅告警） */
    IMAGE_LOW_RATIO,

    /** 嵌入图片：段落/文档内嵌图片仅引用无文字 */
    IMAGE_EMBEDDED,

    /** 表格规则失败：列对齐聚类失败，区域降级为段落 */
    TABLE_RULE_FAILED,

    /** 版面规则失败：版面异常，按顺序输出（预留：当前无事实产出方，见 SignalType.LAYOUT） */
    LAYOUT_RULE_FAILED
}
