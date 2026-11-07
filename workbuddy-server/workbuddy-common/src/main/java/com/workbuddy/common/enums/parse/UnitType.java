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

    /**
     * 虚拟页（DOCX / DOC）：Word 物理页由排版引擎决定、POI 读不到，按每单元字符预算折算切块，
     * 单元序号与 Word 实际页码不保证一致，只作内容体量与质量口径。
     */
    VIRTUAL_PAGE
}
