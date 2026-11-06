package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.worker.parser.pdf.model.PageLine;

import java.util.Set;

/**
 * 页眉元素：本页页眉带内命中已确认键的行各产一条 HEADER。
 */
public final class HeaderBandStep implements PageStep {

    /**
     * 步骤名。
     *
     * @return 固定步骤名
     */
    @Override
    public String name() {
        return "header-band";
    }

    /**
     * 不依赖其他页面步骤。
     *
     * @return 空集合
     */
    @Override
    public Set<String> requires() {
        return Set.of();
    }

    /**
     * 产出 HEADER 元素。
     *
     * @param context 页面工作区
     */
    @Override
    public void apply(PageContext context) {
        for (PageLine line : context.getPage().lines()) {
            if (PageBands.isHeaderLine(context, line)) {
                context.getElements().add(ParagraphAssembler.bandElement(
                        ElementType.HEADER, line.text().trim(), "pdf#top-area", context.getPage().pageNo(),
                        line, context.getFileId(), context.getPage().geometry()));
            }
        }
    }
}
