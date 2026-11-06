package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.worker.parser.pdf.assemble.PageTableProvenance;

import java.util.Set;

/**
 * 表格溯源：为表格与单元格补表格位置路径。
 */
public final class TableProvenanceStep implements PageStep {

    /**
     * 步骤名。
     *
     * @return 固定步骤名
     */
    @Override
    public String name() {
        return "table-provenance";
    }

    /**
     * 依赖元素 ID。
     *
     * @return 依赖步骤名
     */
    @Override
    public Set<String> requires() {
        return Set.of("element-id");
    }

    /**
     * 写表格溯源。
     *
     * @param context 页面工作区
     */
    @Override
    public void apply(PageContext context) {
        PageTableProvenance.assign(context.getElements(), context.getFileId(),
                context.getPage().pageNo());
    }
}
