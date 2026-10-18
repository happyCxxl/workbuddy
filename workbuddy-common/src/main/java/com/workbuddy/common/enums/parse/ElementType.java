package com.workbuddy.common.enums.parse;

/**
 * 元素类型。解析环节只产"解析事实"，标题层级推定（TITLE 类）归组装环节。
 * 当前实际产出：PARAGRAPH / TABLE / TABLE_CELL / IMAGE / HEADER / FOOTER；
 * FIGURE_CAPTION / LIST 枚举保留不产出。
 */
public enum ElementType {

    /** 段落（含无样式标题：只带字体事实，层级归组装环节） */
    PARAGRAPH,

    /** 表格 */
    TABLE,

    /** 表格单元格 */
    TABLE_CELL,

    /** 图片（仅引用+图注，needsOcr 标记） */
    IMAGE,

    /** 图注（保留枚举，暂不专门识别，关联归组装环节） */
    FIGURE_CAPTION,

    /** 页眉（PDF 多页同位置重复 + DOCX 页眉部件；处置归预处理环节） */
    HEADER,

    /** 页脚（PDF 页底多页同位置重复 + 页码模式 + DOCX 页脚部件；处置归预处理环节） */
    FOOTER,

    /** 列表（保留枚举，按段落输出） */
    LIST
}
