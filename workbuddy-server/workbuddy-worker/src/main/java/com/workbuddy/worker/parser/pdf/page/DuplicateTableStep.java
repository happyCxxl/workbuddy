package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.worker.parser.pdf.assemble.TableDeduplicator;

import java.util.Set;

/**
 * 同页重复表合并：同 bbox 的表格保留非空单元格更多的一份。
 */
public final class DuplicateTableStep implements PageStep {

    /**
     * 步骤名。
     *
     * @return 固定步骤名
     */
    @Override
    public String name() {
        return "duplicate-table";
    }

    /**
     * 依赖图片步骤。
     *
     * @return 依赖步骤名
     */
    @Override
    public Set<String> requires() {
        return Set.of("image");
    }

    /**
     * 合并重复表。
     *
     * @param context 页面工作区
     */
    @Override
    public void apply(PageContext context) {
        TableDeduplicator.mergeInPlace(context.getElements(), context,
                context.getPage().pageNo());
    }
}
