package com.workbuddy.worker.parser.pdf.extract;

import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.layout.PageLayoutAnalyzer;
import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.PageLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 行切分与阅读顺序：按栏沟把 y 带切成行、判定跨栏行、按栏序重排整页行。
 * 坐标口径为左上原点，与字符事实一致。
 */
public final class PdfLineSupport {

    /** 跨栏行判定：中心落在非主栏的字符占比下限（长行只是溢出栏宽，越栏字符很少） */
    private static final double SPANNING_COLUMN_MIN_SHARE = 0.3;

    private PdfLineSupport() {
    }

    /**
     * 栏沟检测：字符级投影交给版面端口（返回空即单栏）。
     *
     * @param chars      页面字符
     * @param analyzer   版面分析器
     * @param pageWidth  页宽
     * @param pageHeight 页高
     * @param options    版面判定阈值
     * @return 栏沟中心 x（升序；空表示单栏）
     */
    public static List<Double> gutters(List<CharInfo> chars, PageLayoutAnalyzer analyzer,
                                       double pageWidth, double pageHeight, PageLayoutAnalyzer.Options options) {
        List<PageLayoutAnalyzer.Box> boxes = chars.stream()
                .map(c -> new PageLayoutAnalyzer.Box(c.x(), c.y(), c.width(), c.height()))
                .toList();
        return analyzer.gutters(new PageLayoutAnalyzer.Input(pageWidth, pageHeight, boxes), options);
    }

    /**
     * 按栏沟切行：单栏页整带成一行；多栏页在同一 y 带内按栏沟断口切开后各自成行。
     * 真正横跨栏沟的连续文本（跨栏行）整带出一行。
     *
     * @param pageNo         页码
     * @param lineChars      同一 y 带的字符
     * @param gutters        栏沟中心 x
     * @param lineTextOptions 行文本重建阈值
     * @param properties     解析阈值配置
     * @return 该 y 带切出的行
     */
    public static List<PageLine> buildLines(int pageNo, List<CharInfo> lineChars, List<Double> gutters,
                                            LineTexts.Options lineTextOptions, ParseProperties properties) {
        if (gutters.isEmpty()) {
            return List.of(PdfTokenSupport.buildLine(pageNo, lineChars, lineTextOptions));
        }
        return splitAtGutterBreaks(lineChars, gutters, properties).stream()
                .map(group -> PdfTokenSupport.buildLine(pageNo, group, lineTextOptions))
                .toList();
    }

    /**
     * 阅读顺序重排：跨栏行分带，带内按栏序与栏内纵向顺序（单栏页保持纵向顺序）。
     *
     * @param lines      原始行
     * @param gutters    栏沟中心 x
     * @param analyzer   版面分析器
     * @param pageWidth  页宽
     * @param pageHeight 页高
     * @return 按阅读顺序排列的行
     */
    public static List<PageLine> orderLines(List<PageLine> lines, List<Double> gutters,
                                            PageLayoutAnalyzer analyzer, double pageWidth, double pageHeight) {
        if (gutters.isEmpty() || lines.size() < 2) {
            return lines;
        }
        List<PageLayoutAnalyzer.Box> boxes = lines.stream()
                .map(line -> orderBox(line, gutters))
                .toList();
        PageLayoutAnalyzer.Result result = analyzer.order(
                new PageLayoutAnalyzer.Input(pageWidth, pageHeight, boxes), gutters);
        return result.placed().stream().map(placed -> lines.get(placed.index())).toList();
    }

    /**
     * 在栏沟处的断口切分一条 y 带：沿字符产出顺序走，相邻字符之间出现断口（间隙达词间间隙口径）
     * 或起点回跳/重叠，且下一字符已落到另一栏时切一刀。返回各组字符（未切时为单组）。
     */
    private static List<List<CharInfo>> splitAtGutterBreaks(List<CharInfo> lineChars, List<Double> gutters,
                                                            ParseProperties properties) {
        List<List<CharInfo>> groups = new ArrayList<>();
        List<CharInfo> group = new ArrayList<>();
        Map<Integer, Integer> columns = new LinkedHashMap<>();
        double right = -1;
        double fontSize = 0;
        for (CharInfo c : lineChars) {
            if (!group.isEmpty()) {
                double breakAt = c.x() - right;
                boolean broken = breakAt >= properties.getWordGapRatio() * Math.max(fontSize, 1) || breakAt < 0;
                if (broken && dominantColumn(columns)
                        != PageLayoutAnalyzer.columnOfCenter(c.x() + c.width() / 2, gutters)) {
                    groups.add(group);
                    group = new ArrayList<>();
                    columns = new LinkedHashMap<>();
                    fontSize = 0;
                }
            }
            if (group.isEmpty()) {
                right = c.x();
            }
            group.add(c);
            columns.merge(PageLayoutAnalyzer.columnOfCenter(c.x() + c.width() / 2, gutters), 1, Integer::sum);
            right = Math.max(right, c.x() + c.width());
            fontSize = Math.max(fontSize, c.fontSize());
        }
        if (!group.isEmpty()) {
            groups.add(group);
        }
        return groups;
    }

    /** 组内字符中心最多的栏（组归属的栏） */
    private static int dominantColumn(Map<Integer, Integer> columns) {
        return columns.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(0);
    }

    /**
     * 排顺序用的行盒：单栏行按所属栏裁剪横向范围（长行溢出栏宽不算跨栏行），
     * 真正的跨栏行保持原宽（由它切上下带）。
     */
    private static PageLayoutAnalyzer.Box orderBox(PageLine line, List<Double> gutters) {
        if (!PageLayoutAnalyzer.crossesAny(line.x(), line.width(), gutters)
                || spansColumns(line, gutters, SPANNING_COLUMN_MIN_SHARE)) {
            return new PageLayoutAnalyzer.Box(line.x(), line.y(), line.width(), line.height());
        }
        int column = PageLayoutAnalyzer.columnOfCenter(line.x() + line.width() / 2, gutters);
        double left = column == 0 ? line.x() : Math.max(line.x(), gutters.get(column - 1));
        double right = column == gutters.size() ? line.x() + line.width()
                : Math.min(line.x() + line.width(), gutters.get(column));
        return new PageLayoutAnalyzer.Box(left, line.y(), Math.max(right - left, 0), line.height());
    }

    /** 行是否真正跨栏：中心落在非主栏的字符占比达门槛（只是长行溢出栏宽的行不算） */
    private static boolean spansColumns(PageLine line, List<Double> gutters, double minShare) {
        List<CharInfo> chars = line.chars();
        if (chars.isEmpty()) {
            return false;
        }
        Map<Integer, Integer> countByColumn = new LinkedHashMap<>();
        for (CharInfo c : chars) {
            countByColumn.merge(
                    PageLayoutAnalyzer.columnOfCenter(c.x() + c.width() / 2, gutters), 1, Integer::sum);
        }
        int dominant = countByColumn.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return (double) (chars.size() - dominant) / chars.size() >= minShare;
    }
}
