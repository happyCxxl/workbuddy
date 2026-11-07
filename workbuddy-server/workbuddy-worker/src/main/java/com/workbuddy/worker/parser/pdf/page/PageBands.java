package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.common.domain.parse.UnitLine;
import com.workbuddy.worker.structure.craft.HeaderFooterDetector;
import com.workbuddy.worker.parser.pdf.model.PageLine;

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
     * @return 命中为 true
     */
    public static boolean isFooterLine(PageContext context, PageLine line) {
        if (line.y() + line.height() < footerBandY(context)) {
            return false;
        }
        String footerKey = HeaderFooterDetector.footerBase(HeaderFooterDetector.normalizeKey(line.text()));
        return context.getKeys().footers().matches(footerKey, unitLine(line), context.getUnit(),
                context.getProperties().bandRules());
    }

    /** 页面行 → 单元行摘要（同为版面坐标口径，字段一一对应） */
    private static UnitLine unitLine(PageLine line) {
        return new UnitLine(line.text(), line.x(), line.y(), line.width(), line.height());
    }
}
