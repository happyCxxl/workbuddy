package com.workbuddy.common.enums.structure;

/**
 * 关系类型（字典）：章节树 + 阅读顺序 + 归属 + 跨页延续 + 派生来源（全平台血缘核心纽带）。
 * 落库/落产物形式：name()。
 *
 * @author cxxl
 */
public enum RelationType {

    /** 父子（章节树层级；标题/章节节点指向其子内容） */
    PARENT_CHILD,

    /** 下一元素（正文阅读顺序链） */
    NEXT,

    /** 上一元素（正文阅读顺序链） */
    PREVIOUS,

    /** 单元格归属（TABLE_CELL → TABLE） */
    TABLE_CELL_OF,

    /** 图注归属（IMAGE ← FIGURE_CAPTION；预留未实现） */
    CAPTION_OF,

    /** 跨页接续（续表后续页 → 首页；from/to 带 #p页码 后缀） */
    CONTINUATION_OF,

    /** 派生来源（下游产物元素 → 上游产物元素；预留） */
    DERIVED_FROM
}
