package com.workbuddy.worker.parser.pdf.extract.pipeline;

import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.detect.RuleRegions;
import com.workbuddy.worker.parser.pdf.extract.LineFragmentMerger;
import com.workbuddy.worker.parser.pdf.extract.LineTexts;
import com.workbuddy.worker.parser.pdf.extract.PdfLineSupport;
import com.workbuddy.worker.parser.pdf.extract.PdfTableBandSupport;
import com.workbuddy.worker.parser.pdf.extract.PdfTokenSupport;
import com.workbuddy.worker.parser.pdf.layout.PageLayoutAnalyzer;
import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.Region;
import com.workbuddy.worker.parser.pdf.model.Segment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 行构建：y 带聚合、表格带整带成行、栏沟切行、碎片合并、栏沟复核与阅读顺序重排。
 */
public final class LineBuildStep implements ExtractionStep {

    private final PageLayoutAnalyzer layoutAnalyzer;

    /**
     * 构造行构建步骤。
     *
     * @param layoutAnalyzer 版面分析器
     */
    public LineBuildStep(PageLayoutAnalyzer layoutAnalyzer) {
        this.layoutAnalyzer = layoutAnalyzer;
    }

    /**
     * 步骤名。
     *
     * @return 固定步骤名
     */
    @Override
    public String name() {
        return "line-build";
    }

    /**
     * 构建页内行。
     *
     * @param context 单页提取工作区
     */
    @Override
    public void apply(ExtractionContext context) {
        ParseProperties properties = context.parseContext().getProperties();
        double pageWidth = context.geometry().textWidth();
        double pageHeight = context.geometry().textHeight();
        List<CharInfo> sorted = context.stripped().chars().stream()
                .sorted(Comparator.comparingDouble(CharInfo::y))
                .toList();
        List<Double> gutters = PdfLineSupport.gutters(context.stripped().chars(), layoutAnalyzer,
                pageWidth, pageHeight, PageLayoutAnalyzer.Options.of(properties));
        LineTexts.Options lineTextOptions = LineTexts.Options.of(properties);
        List<List<CharInfo>> bands = PdfTableBandSupport.groupBands(sorted,
                properties.getLineYTolerance());
        double alignmentTolerance = PdfTableBandSupport.alignmentTolerance(bands, properties);
        boolean[] tableBands = PdfTableBandSupport.tableBands(bands, properties, alignmentTolerance);
        List<PageLine> rawLines = buildLines(context.pageNo(), bands, tableBands, gutters,
                lineTextOptions, properties);
        List<PageLine> mergedLines = LineFragmentMerger.merge(rawLines, pageWidth,
                properties.getLineYTolerance(), lineTextOptions);
        if (!gutters.isEmpty()
                && PdfLineSupport.spanningShare(outsideRegions(mergedLines, context.graphics().segments(),
                        pageWidth, pageHeight, properties), gutters)
                > properties.getColumnGutterMaxSpanShare()) {
            gutters = List.of();
            rawLines = buildLines(context.pageNo(), bands, tableBands, gutters, lineTextOptions, properties);
            mergedLines = LineFragmentMerger.merge(rawLines, pageWidth,
                    properties.getLineYTolerance(), lineTextOptions);
        }
        context.setLines(PdfLineSupport.orderLines(mergedLines, gutters, layoutAnalyzer,
                pageWidth, pageHeight));
    }

    /** 逐 y 带成行：表格带整带一条（不按栏切），其余带按栏沟断口切行。 */
    private static List<PageLine> buildLines(int pageNo, List<List<CharInfo>> bands, boolean[] tableBands,
                                             List<Double> gutters, LineTexts.Options lineTextOptions,
                                             ParseProperties properties) {
        List<PageLine> lines = new ArrayList<>();
        for (int i = 0; i < bands.size(); i++) {
            if (tableBands[i]) {
                lines.add(PdfTokenSupport.buildLine(pageNo, bands.get(i), lineTextOptions));
            } else {
                lines.addAll(PdfLineSupport.buildLines(pageNo, bands.get(i), gutters, lineTextOptions, properties));
            }
        }
        return lines;
    }

    /** 线框区域外的行：栏沟复核只看正文行。 */
    private static List<PageLine> outsideRegions(List<PageLine> lines, List<Segment> segments,
                                                 double pageWidth, double pageHeight,
                                                 ParseProperties properties) {
        List<Region> regions = RuleRegions.regions(segments, properties.getTableLineCoverRatio(),
                pageWidth, pageHeight);
        if (regions.isEmpty()) {
            return lines;
        }
        return lines.stream()
                .filter(line -> regions.stream().noneMatch(region -> region.contains(
                        line.x() + line.width() / 2, line.y() + line.height() / 2)))
                .toList();
    }
}
