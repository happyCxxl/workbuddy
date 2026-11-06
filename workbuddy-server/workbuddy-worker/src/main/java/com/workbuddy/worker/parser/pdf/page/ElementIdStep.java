package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.worker.parser.pdf.assemble.PageElementIds;

import java.util.Set;

/**
 * 元素 ID：按元素顺序分配稳定 ID，单元格随所属表格编号。
 */
public final class ElementIdStep implements PageStep {

    /**
     * 步骤名。
     *
     * @return 固定步骤名
     */
    @Override
    public String name() {
        return "element-id";
    }

    /**
     * 依赖去重步骤。
     *
     * @return 依赖步骤名
     */
    @Override
    public Set<String> requires() {
        return Set.of("duplicate-table");
    }

    /**
     * 分配元素 ID。
     *
     * @param context 页面工作区
     */
    @Override
    public void apply(PageContext context) {
        PageElementIds.assign(context.getElements(), context.getPage().pageNo());
    }
}
