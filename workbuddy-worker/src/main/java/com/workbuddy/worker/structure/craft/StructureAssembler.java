package com.workbuddy.worker.structure.craft;

import com.workbuddy.common.domain.structure.DocumentRelation;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.worker.structure.AssembleContext;

import java.util.List;

/**
 * 结构组装：标题推定（样式→编号模式→字号/加粗）+ Excel sheet→SECTION；
 * 关系（章节树 PARENT_CHILD + 阅读顺序 NEXT/PREVIOUS + 表格归属 TABLE_CELL_OF）在跨页接续之后按最终元素表重建，
 * 保证关系端点都是存活元素。
 *
 * @author cxxl
 */
public interface StructureAssembler {

    /**
     * 组装结构（标题推定 + Excel 章节插入）。
     *
     * @param ordered 阅读顺序后的元素
     * @param context 组装上下文
     * @return 树产出（元素/推定统计）
     */
    TreeOutcome assembleTree(List<UnifiedElement> ordered, AssembleContext context);

    /**
     * 按最终元素表重建关系（跨页接续之后调用）。
     *
     * @param elements 最终元素表（含接续合并结果与 Excel 章节节点）
     * @param context  组装上下文
     * @return 关系列表（章节树 / 阅读顺序 / 单元格归属）
     */
    List<DocumentRelation> buildRelations(List<UnifiedElement> elements, AssembleContext context);
}
