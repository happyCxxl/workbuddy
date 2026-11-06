package com.workbuddy.worker.parser.pdf.pipeline;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.worker.parser.pdf.assemble.TableContinuationSupport;

import java.util.List;

/**
 * 跨页续表步骤：全部页面装配完成后，按页序标记续表。
 */
public final class TableContinuationStep
        implements DocumentStep<TableContinuationStep.Input, List<ParseElement>> {

    /**
     * 续表标记输入。
     *
     * @param elements 全档元素
     * @param overlapRatio 列区间重合比例下限
     */
    public record Input(List<ParseElement> elements, double overlapRatio) {
    }

    /**
     * 执行续表标记。
     *
     * @param input 续表输入
     * @return 标记后的元素
     */
    @Override
    public List<ParseElement> execute(Input input) {
        TableContinuationSupport.markContinuedTables(input.elements(), input.overlapRatio());
        return input.elements();
    }
}
