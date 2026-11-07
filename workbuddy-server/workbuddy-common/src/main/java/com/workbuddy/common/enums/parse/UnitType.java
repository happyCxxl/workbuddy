package com.workbuddy.common.enums.parse;

/**
 * 解析单元类型：解析产出的最小组织单位，按格式不同取值不同。
 */
public enum UnitType {

    /** 物理页（PDF / 图片） */
    PAGE,

    /** 幻灯片（PPTX / PPT） */
    SLIDE,

    /** 工作表（XLSX / XLS） */
    SHEET,

    /** 段落/表格流（DOCX / DOC），不承诺物理页 */
    BLOCK_STREAM
}
