package com.workbuddy.common.enums.parse;

/**
 * 质量告警码（落产物 JSON quality.warnings[].code，与详情页/下游环节的展示契约）。
 * 跨环节共享（解析/组装等环节均经 QualityWarning 落告警）。
 *
 * @author cxxl
 */
public enum QualityWarningCode {

    // ---- 解析环节 ----

    /** 乱码页：乱码率超阈值，计失败页 */
    GARBLED_PAGE,

    /** 乱码页（CID 映射失败）：字体缺 ToUnicode 映射，计失败页 */
    CID_MAPPING_FAILED_PAGE,

    /** 扫描页：无文本层且图片覆盖高，OCR 预留暂不支持，计失败页 */
    SCANNED_PAGE,

    /** 空白页：无文本且无图片覆盖，不计失败页 */
    BLANK_PAGE,

    /** 纯矢量页：无文本无图片但有矢量图形覆盖（图表/示意图），不计失败页 */
    VECTOR_PAGE_SUSPECTED,

    /** 嵌入图片文字未识别（OCR 预留，暂仅记录引用与图注），不计失败 */
    IMAGE_TEXT_UNRECOGNIZED,

    /** 疑似图片页：文字占比低于阈值（仅告警，OCR 预留），不计失败 */
    IMAGE_PAGE_SUSPECTED,

    /** 表格规则失败，区域降级为段落，不计失败 */
    TABLE_RULE_FALLBACK,

    /** 版面规则失败，按顺序输出，不计失败（预留：当前无事实产出方，见 SignalType.LAYOUT） */
    LAYOUT_RULE_FALLBACK,

    /** 质量维度汇总：坏页比例达门槛，按维度列出各类计数（仅告警，不改变逐条告警语义） */
    QUALITY_BAD_PAGE_RATIO,

    // ---- 组装环节 ----

    /** 标题候选：规则拿不准，已按正文处理 */
    TITLE_CANDIDATE,

    /** 疑似续表：放宽规则命中，默认接续 + 表头继承 */
    SUSPECTED_CONTINUATION,

    /** 溯源缺失：部分元素缺原文定位 */
    PROVENANCE_MISSING,

    /** 噪声页：空白/纯图片/乱码页已标记 */
    NOISE_PAGE,

    /** 未识别的解析元素类型：标准化跳过该元素（跨环节类型集合不匹配） */
    ELEMENT_TYPE_UNMAPPED
}
