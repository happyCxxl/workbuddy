package com.workbuddy.worker.structure.craft;

import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.worker.structure.AssembleContext;

import java.util.List;

/**
 * 跨页续表接续：四条件（相邻页+页尾/页首+表头一致+列数相同）+
 * 放宽规则（列数+列宽模式一致，标疑似）——只针对 PDF 表格。
 */
public interface TableContinuationResolver {

    /**
     * 接续合并。
     *
     * @param ordered 阅读顺序后的元素
     * @param context 组装上下文
     * @return 接续产出（元素/接续表数）
     */
    ContinuationOutcome joinContinuations(List<UnifiedElement> ordered, AssembleContext context);
}
