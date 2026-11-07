package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.worker.parser.pdf.model.PageLine;


/**
 * 页脚候选：页底带内命中页脚键或页码位置的行先暂存，正文结算后再追加。
 */
public final class FooterCollectStep implements PageStep {

    /**
     * 步骤名。
     *
     * @return 固定步骤名
     */
    @Override
    public String name() {
        return "footer-collect";
    }

    /**
     * 收集 FOOTER 元素到暂存列表。
     *
     * @param context 页面工作区
     */
    @Override
    public void apply(PageContext context) {
        for (PageLine line : context.getPage().lines()) {
            if (PageBands.isFooterLine(context, line)) {
                context.getFooterElements().add(ParagraphAssembler.bandElement(
                        ElementType.FOOTER, line.text().trim(), "pdf#bottom-area", context.getPage().pageNo(),
                        line, context.getFileId(), context.getPage().geometry()));
            }
        }
    }
}
