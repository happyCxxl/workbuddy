package com.workbuddy.worker.parser.pdf.extract;

import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.utils.TextUtil;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.pdf.detect.RuleSegments;
import com.workbuddy.worker.parser.pdf.layout.PageLayoutAnalyzer;
import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.PdfPageGeometry;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 单页提取：字符 + 坐标 + 字体 → 页级指标（字符数/乱码率/文字占比/图片面积）→
 * 行聚合（y 容差）→ 按栏沟切行 → 阅读顺序重排 → token 分词，产出 {@link PageContent}。
 */
public class PdfPageExtractor {

    private final PageLayoutAnalyzer layoutAnalyzer;

    /**
     * 构造页提取器。
     *
     * @param layoutAnalyzer 版面分析器
     */
    public PdfPageExtractor(PageLayoutAnalyzer layoutAnalyzer) {
        this.layoutAnalyzer = layoutAnalyzer;
    }

    /** 占比（分母为 0 时返回 0） */
    private static double ratio(int count, int total) {
        return total > 0 ? (double) count / total : 0;
    }

    /**
     * 提取一页：字符级指标、行聚合、栏切分、阅读顺序、文字占比。
     *
     * @param doc     PDF 文档
     * @param pageNo  页码（从 1 开始）
     * @param context 解析上下文
     * @return 单页提取内容
     * @throws IOException 读取页面内容失败
     */
    public PageContent extractPage(PDDocument doc, int pageNo, ParseContext context) throws IOException {
        PdfTokenSupport.StrippedPage stripped = PdfTokenSupport.strip(doc, pageNo);

        PDPage page = doc.getPage(pageNo - 1);
        // 版面尺寸取裁剪框（未设裁剪框时 PDFBox 回落到媒体框）：与渲染出来的可见页一致，
        // 页眉页脚带比例、贴页底判定都按它算；产出元素时再按页几何换算到视口坐标
        PdfPageGeometry geometry = PdfPageGeometry.of(page);
        double pageWidth = geometry.textWidth();
        double pageHeight = geometry.textHeight();

        // 页级指标（字符统计先行；文字占比改在行聚合后按行级 bbox 计算，见下方）
        PageMetric metric = new PageMetric();
        metric.setPage(pageNo);
        Set<String> unmappedFonts = stripped.unmappedFontNames();
        int nonBlank = 0;
        int nonCommon = 0;
        int printableMojibake = 0;
        int unmapped = 0;
        for (CharInfo c : stripped.chars()) {
            if (Character.isWhitespace(c.codePoint())) {
                continue;
            }
            nonBlank++;
            if (TextUtil.isNonCommonChar(c.codePoint())) {
                nonCommon++;
            }
            if (TextUtil.isPrintableMojibakeChar(c.codePoint())) {
                printableMojibake++;
            }
            if (unmappedFonts.contains(c.fontName())) {
                unmapped++;
            }
        }
        metric.setCharCount(nonBlank);
        metric.setCidMappingRatio(ratio(unmapped, nonBlank));
        // 乱码率：不可读码点占比；可打印乱码带达阈值时按两者较大取（字体映射错位页由此不再漏判）
        double unreadableRatio = ratio(nonCommon, nonBlank);
        double printableRatio = ratio(printableMojibake, nonBlank);
        metric.setGarbledRatio(printableRatio >= context.getProperties().getPrintableGarbledRateThreshold()
                ? Math.max(unreadableRatio, printableRatio) : unreadableRatio);
        RuleSegments.Graphics graphics = RuleSegments.collect(page, pageHeight);
        metric.setImageAreaRatio(RuleSegments.imageAreaRatio(graphics.imageBoxes(), pageWidth, pageHeight));
        metric.setVectorAreaRatio(RuleSegments.vectorAreaRatio(graphics.segments(), pageWidth, pageHeight));

        // 行聚合（y 容差聚类，同 y 内保持产出顺序 → 栏沟处按断口切行 → 行内按 x 排序 → token 分词）
        List<CharInfo> sorted = stripped.chars().stream()
                .sorted(Comparator.comparingDouble(CharInfo::y))
                .toList();
        List<Double> gutters = PdfLineSupport.gutters(stripped.chars(), layoutAnalyzer, pageWidth, pageHeight,
                PageLayoutAnalyzer.Options.of(context.getProperties()));
        LineTexts.Options lineTextOptions = LineTexts.Options.of(context.getProperties());
        List<List<CharInfo>> bands = PdfTableBandSupport.groupBands(sorted,
                context.getProperties().getLineYTolerance());
        double alignmentTolerance = PdfTableBandSupport.alignmentTolerance(bands, context.getProperties());
        boolean[] tableBands = PdfTableBandSupport.tableBands(bands, context.getProperties(), alignmentTolerance);
        List<PageLine> rawLines = new ArrayList<>();
        for (int i = 0; i < bands.size(); i++) {
            if (tableBands[i]) {
                rawLines.add(PdfTokenSupport.buildLine(pageNo, bands.get(i), lineTextOptions));
            } else {
                rawLines.addAll(PdfLineSupport.buildLines(pageNo, bands.get(i), gutters, lineTextOptions,
                        context.getProperties()));
            }
        }
        List<PageLine> mergedLines = LineFragmentMerger.merge(rawLines, pageWidth,
                context.getProperties().getLineYTolerance(), lineTextOptions);
        List<PageLine> lines = PdfLineSupport.orderLines(mergedLines, gutters, layoutAnalyzer, pageWidth, pageHeight);

        // 文字占比：行级 bbox 累计（行宽 × 行高，行高取 max(字符高, 字号×1.2)），
        // 字符级小盒子累计会低估占版面积、误伤稀疏页
        double textArea = 0;
        for (PageLine line : lines) {
            double lineHeight = Math.max(line.height(), line.fontSize() * 1.2);
            textArea += line.width() * lineHeight;
        }
        metric.setTextAreaRatio(pageWidth * pageHeight > 0 ? textArea / (pageWidth * pageHeight) : 0);

        return new PageContent(pageNo, pageWidth, pageHeight, geometry, metric, lines, graphics.segments(),
                graphics.imageBoxes());
    }
}
