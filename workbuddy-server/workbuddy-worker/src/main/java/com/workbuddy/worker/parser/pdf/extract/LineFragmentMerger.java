package com.workbuddy.worker.parser.pdf.extract;

import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.PageLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 行片段合并：把同一页、同一 y 带、同字号、被栏沟切断的相邻行片段合并回一条视觉行。
 *
 * <p>栏沟投影会把跨栏标题/页眉/页脚/页码切成多个 {@link PageLine}，后续页眉页脚识别、
 * 段落聚合与阅读顺序都会把它当成多条独立行。本类在阅读顺序重排之前做一次合并：
 * 小间隙片段直接合并（页眉/页脚/页码/URL）；大间隙只在字号明显大于本页正文字号时合并（标题）。
 * 真实双栏的栏距通常大于小间隙门槛，不会被误合并。
 *
 * <p>不处理空白片段（空白段落由组装侧过滤），不回写页面内容。
 */
public final class LineFragmentMerger {

    /** 同 y 带内字号容差（pt） */
    private static final double FONT_SIZE_TOLERANCE = 0.6;

    /** 小间隙门槛 = 字号 × 该系数（pts） */
    private static final double SMALL_GAP_FONT_FACTOR = 0.3;

    /** 大间隙门槛 = 字号 × 该系数（pts） */
    private static final double LARGE_GAP_FONT_FACTOR = 2.0;

    /** 大字号判定 = 本页字号中位数 × 该系数 */
    private static final double HEADING_FONT_FACTOR = 1.2;

    /** 合并后行的最大宽度 = 页宽 × 该系数（防止跨栏合并） */
    private static final double MAX_COMBINED_WIDTH_RATIO = 0.6;

    private LineFragmentMerger() {
    }

    /**
     * 合并同一 y 带内的行片段。
     *
     * @param lines      原始行（同一页）
     * @param pageWidth  页宽
     * @param yTolerance 同 y 带容差
     * @param options    行文本重建口径
     * @return 合并后的行（保持页内顺序，随后交给阅读顺序重排）
     */
    public static List<PageLine> merge(List<PageLine> lines, double pageWidth,
                                       double yTolerance, LineTexts.Options options) {
        List<PageLine> ordered = new ArrayList<>(lines);
        ordered.sort(Comparator.comparingInt(PageLine::page)
                .thenComparingDouble(PageLine::y)
                .thenComparingDouble(PageLine::x));
        double medianFontSize = medianFontSize(ordered);
        // 同一 x 在多行重复出现 = 栏起点；跨栏片段即使文本重叠/间隙极小也不能合并
        Map<Long, Integer> xCounts = new HashMap<>();
        for (PageLine line : ordered) {
            xCounts.merge(Math.round(line.x()), 1, Integer::sum);
        }
        Set<Long> columnStarts = xCounts.entrySet().stream()
                .filter(entry -> entry.getValue() >= 2)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        List<PageLine> result = new ArrayList<>();
        for (PageLine line : ordered) {
            if (!tryMergeInto(result, line, pageWidth, yTolerance, medianFontSize, columnStarts, options)) {
                result.add(line);
            }
        }
        return result;
    }

    /**
     * 尝试与已合并的相邻行合并。
     *
     * @param result        已合并行
     * @param line          当前行
     * @param pageWidth     页宽
     * @param yTolerance    同 y 带容差
     * @param medianFontSize 本页字号中位数
     * @param columnStarts   本页重复出现的栏起点 x
     * @param options       行文本重建口径
     * @return 发生合并返回 true
     */
    private static boolean tryMergeInto(List<PageLine> result, PageLine line, double pageWidth,
                                        double yTolerance, double medianFontSize,
                                        Set<Long> columnStarts, LineTexts.Options options) {
        String currentText = textOf(line);
        for (int i = result.size() - 1; i >= 0; i--) {
            PageLine previous = result.get(i);
            String previousText = textOf(previous);
            if (previous.page() != line.page()
                    || Math.abs(previous.y() - line.y()) > yTolerance
                    || Math.abs(previous.fontSize() - line.fontSize()) > FONT_SIZE_TOLERANCE
                    || previousText.isEmpty() || currentText.isEmpty()) {
                continue;
            }
            double gap = line.x() - (previous.x() + previous.width());
            double fontSize = Math.max(previous.fontSize(), line.fontSize());
            double smallGap = Math.max(1.5, fontSize * SMALL_GAP_FONT_FACTOR);
            double largeGap = Math.max(2.0, fontSize * LARGE_GAP_FONT_FACTOR);
            double allowedGap = fontSize >= medianFontSize * HEADING_FONT_FACTOR ? largeGap : smallGap;
            double left = Math.min(previous.x(), line.x());
            double right = Math.max(previous.x() + previous.width(), line.x() + line.width());
            if (gap > allowedGap) {
                continue;
            }
            boolean wide = right - left > pageWidth * MAX_COMBINED_WIDTH_RATIO;
            // 只有“大字号大间距”的标题才需要宽度上限防止跨栏；相邻/重叠的小间隙片段
            // 本来就是同一视觉行，不能因为整行接近页宽而拒绝合并
            if (gap > smallGap && wide) {
                continue;
            }
            // 宽行才可能是跨栏；窄的页脚/页码片段即使 x 与正文栏起点重合也必须合并
            if (wide && columnStarts.contains(Math.round(line.x()))) {
                continue;
            }
            List<CharInfo> chars = new ArrayList<>(previous.chars());
            chars.addAll(line.chars());
            chars.sort(Comparator.comparingDouble(CharInfo::x));
            PageLine merged = PdfTokenSupport.buildLine(previous.page(), chars, options);
            if (gap > 0 && Character.isLetterOrDigit(previousText.charAt(previousText.length() - 1))
                    && Character.isLetterOrDigit(currentText.charAt(0))) {
                merged = withText(merged, previousText + " " + currentText);
            }
            result.set(i, merged);
            return true;
        }
        return false;
    }

    /** 取非空文本（null 安全） */
    private static String textOf(PageLine line) {
        String text = line.text();
        return text == null ? "" : text.trim();
    }

    /** 复制行并替换文本 */
    private static PageLine withText(PageLine line, String text) {
        return new PageLine(line.page(), line.x(), line.y(), line.width(), line.height(), text,
                line.fontSize(), line.fontName(), line.bold(), line.tokens(), line.chars(), line.tocCandidate());
    }

    /** 本页行字号中位数 */
    private static double medianFontSize(List<PageLine> lines) {
        if (lines.isEmpty()) {
            return 0;
        }
        List<Double> sizes = new ArrayList<>();
        for (PageLine line : lines) {
            sizes.add(line.fontSize());
        }
        sizes.sort(Double::compare);
        return sizes.get(sizes.size() / 2);
    }
}
