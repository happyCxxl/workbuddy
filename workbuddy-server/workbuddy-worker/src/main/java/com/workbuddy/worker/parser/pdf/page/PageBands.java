package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.worker.parser.pdf.detect.HeaderFooterDetector;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.PageLines;
import com.workbuddy.worker.parser.pdf.model.TextLine;

/**
 * 页眉/页脚带判定：带边界与命中条件集中在一处，供收集与正文过滤共用。
 */
public final class PageBands {

    private PageBands() {
    }

    /**
     * 页眉带下边界。
     *
     * @param context 页面工作区
     * @return 页眉带 y 边界
     */
    public static double headerBandY(PageContext context) {
        return context.getPage().pageHeight() * context.getProperties().getHeaderAreaRatio();
    }

    /**
     * 页脚带上边界。
     *
     * @param context 页面工作区
     * @return 页脚带 y 边界
     */
    public static double footerBandY(PageContext context) {
        return context.getPage().pageHeight() * (1 - context.getProperties().getFooterAreaRatio());
    }

    /**
     * 行是否命中页眉带内已确认键。
     *
     * @param context 页面工作区
     * @param line 页面行
     * @return 命中为 true
     */
    public static boolean isHeaderLine(PageContext context, PageLine line) {
        String key = HeaderFooterDetector.normalizeKey(line.text());
        return line.y() < headerBandY(context) && context.getKeys().headers().contains(key);
    }

    /**
     * 行是否命中页底页脚键或页码位置。
     *
     * @param context 页面工作区
     * @param line 页面行
     * @param pageLines 全页行
     * @return 命中为 true
     */
    public static boolean isFooterLine(PageContext context, PageLine line, PageLines pageLines) {
        if (line.y() + line.height() < footerBandY(context)) {
            return false;
        }
        String footerKey = HeaderFooterDetector.footerBase(HeaderFooterDetector.normalizeKey(line.text()));
        return context.getKeys().footers().matches(footerKey, TextLine.of(line), pageLines,
                context.getProperties());
    }
}
