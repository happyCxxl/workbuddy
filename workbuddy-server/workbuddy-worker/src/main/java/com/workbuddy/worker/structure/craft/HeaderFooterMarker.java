package com.workbuddy.worker.structure.craft;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.ParseUnitResult;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.worker.structure.craft.HeaderFooterDetector.BandRules;
import com.workbuddy.worker.structure.craft.HeaderFooterDetector.FooterKeys;

import java.util.List;
import java.util.Set;

/**
 * 页眉页脚标注（组装侧）：按文档级判定结果，把「整体落在页眉/页脚带内且文本命中已确认键」的元素改类型。
 *
 * <p>只改元素类型，不切行、不改元素顺序与 ID：解析环节按正文产出全部元素，页眉页脚的裁决在组装。
 * 元素包围盒是视口坐标，带边界按单元视口高度取；行摘要判定用版面坐标——两者在无旋转页上同口径。
 */
public final class HeaderFooterMarker {

    /** 包围盒与带边界的容差（pt） */
    private static final double EDGE_TOLERANCE = 0.5;

    private HeaderFooterMarker() {
    }

    /**
     * 标注一路解析结果里的页眉/页脚元素。
     *
     * @param sources 解析结果
     * @param rules   判定阈值
     * @return 改类型的元素数
     */
    public static int mark(List<ParseSource> sources, BandRules rules) {
        int marked = 0;
        for (ParseSource source : sources) {
            List<ParseUnitResult> units = source.getUnits();
            if (units == null || units.isEmpty()) {
                continue;
            }
            Set<String> headerKeys = HeaderFooterDetector.detectHeaders(units, rules);
            FooterKeys footerKeys = HeaderFooterDetector.detectFooters(units, rules);
            for (ParseElement element : source.getElements()) {
                if (markElement(element, units, headerKeys, footerKeys, rules)) {
                    marked++;
                }
            }
        }
        return marked;
    }

    /** 单个元素判定与改类型 */
    private static boolean markElement(ParseElement element, List<ParseUnitResult> units,
                                       Set<String> headerKeys, FooterKeys footerKeys, BandRules rules) {
        BBox box = element.getBbox();
        ParseUnitResult unit = element.getPage() == null ? null : unitOf(units, element.getPage());
        if (box == null || unit == null) {
            return false;
        }
        double height = viewportHeight(unit);
        if (height <= 0) {
            return false;
        }
        String text = HeaderFooterDetector.normalizeKey(element.getText());
        if (text.isEmpty()) {
            return false;
        }
        if (withinTopBand(box, height, rules.headerRatio()) && headerKeys.contains(text)) {
            element.setType(ElementType.HEADER.name());
            return true;
        }
        if (withinBottomBand(box, height, rules.footerRatio()) && isFooterText(text, box, footerKeys, rules)) {
            element.setType(ElementType.FOOTER.name());
            return true;
        }
        return false;
    }

    /** 元素文本是否属页脚：页脚基础文本键命中，或页码形态且横向中心落到已确认页码中心 */
    private static boolean isFooterText(String text, BBox box, FooterKeys footerKeys, BandRules rules) {
        String base = HeaderFooterDetector.footerBase(text);
        if (footerKeys.texts().contains(base)) {
            return true;
        }
        if (!HeaderFooterDetector.PAGE_NUMBER_KEY.equals(base)) {
            return false;
        }
        double center = box.getX() + box.getWidth() / 2;
        for (double candidate : footerKeys.pageNumberCenters()) {
            if (Math.abs(candidate - center) <= rules.pageNumberXTolerance()) {
                return true;
            }
        }
        return false;
    }

    /** 包围盒整体落在页顶带 */
    private static boolean withinTopBand(BBox box, double height, double ratio) {
        return box.getY() >= -EDGE_TOLERANCE
                && box.getY() + box.getHeight() <= height * ratio + EDGE_TOLERANCE;
    }

    /** 包围盒整体落在页底带 */
    private static boolean withinBottomBand(BBox box, double height, double ratio) {
        return box.getY() >= height * (1 - ratio) - EDGE_TOLERANCE
                && box.getY() + box.getHeight() <= height + EDGE_TOLERANCE;
    }

    /** 按页号取单元；页号缺失或越界返回 null */
    private static ParseUnitResult unitOf(List<ParseUnitResult> units, int page) {
        return page >= 1 && page <= units.size() ? units.get(page - 1) : null;
    }

    /** 单元视口高度（元素坐标口径）；无尺寸返回 0 */
    private static double viewportHeight(ParseUnitResult unit) {
        if (unit.getDimension() == null) {
            return 0;
        }
        return unit.getDimension().getHeight();
    }
}
