package com.workbuddy.common.enums.structure;

/**
 * 统一文档模型元素类型（字典，14 类）：下游切片环节内容类型路由的依据。
 * 落库/落产物形式：name()。
 *
 * @author cxxl
 */
public enum UnifiedElementType {

    /** 文档根（树根节点，不参与阅读顺序） */
    DOCUMENT,

    /** 页（PDF 页级元素，有页码） */
    PAGE,

    /** 章节（Excel sheet 归并的顶层分组节点） */
    SECTION,

    /** 标题（层级推定产出，level 从 1 起） */
    TITLE,

    /** 段落 */
    PARAGRAPH,

    /** 列表项（暂不专门识别） */
    LIST,

    /** 表格（含 cells 子元素） */
    TABLE,

    /** 表格行（当前以 TABLE_CELL 直挂 TABLE 表达，不产出行元素） */
    TABLE_ROW,

    /** 表格单元格（TABLE 的子元素） */
    TABLE_CELL,

    /** 图片（仅引用 + needsOcr，暂不识别文字） */
    IMAGE,

    /** 图注（暂不专门识别） */
    FIGURE_CAPTION,

    /** 页眉 */
    HEADER,

    /** 页脚 */
    FOOTER,

    /** 公式（暂不专门识别） */
    EQUATION
}
