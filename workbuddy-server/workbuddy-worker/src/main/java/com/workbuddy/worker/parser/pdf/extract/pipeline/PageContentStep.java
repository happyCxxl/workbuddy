package com.workbuddy.worker.parser.pdf.extract.pipeline;

import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;

import java.util.List;

/**
 * 单页内容组装：只拼接几何、指标、行、线段与图片框，并计算行级文字面积占比。
 */
public final class PageContentStep implements ExtractionStep {

    /**
     * 步骤名。
     *
     * @return 固定步骤名
     */
    @Override
    public String name() {
        return "page-content";
    }

    /**
     * 组装页内容。
     *
     * @param context 单页提取工作区
     */
    @Override
    public void apply(ExtractionContext context) {
        double pageWidth = context.geometry().textWidth();
        double pageHeight = context.geometry().textHeight();
        List<PageLine> lines = context.lines();
        double textArea = 0;
        for (PageLine line : lines) {
            double lineHeight = Math.max(line.height(), line.fontSize() * 1.2);
            textArea += line.width() * lineHeight;
        }
        context.metric().setTextAreaRatio(pageWidth * pageHeight > 0
                ? textArea / (pageWidth * pageHeight) : 0);
        context.setContent(new PageContent(context.pageNo(), pageWidth, pageHeight, context.geometry(),
                context.metric(), lines, context.graphics().segments(), context.graphics().imageBoxes()));
    }
}
