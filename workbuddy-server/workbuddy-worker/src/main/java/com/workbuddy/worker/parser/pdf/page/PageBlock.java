package com.workbuddy.worker.parser.pdf.page;

/**
 * 页面块：分类步骤只决定边界，渲染步骤按块类型产出元素。
 */
public sealed interface PageBlock permits ParagraphBlock, TableBlock, RegionTableBlock {
}
