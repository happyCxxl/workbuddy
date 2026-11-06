package com.workbuddy.worker.parser.pdf.extract.pipeline;

import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.utils.TextUtil;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.detect.RuleSegments;
import com.workbuddy.worker.parser.pdf.extract.PdfTokenSupport;
import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.PdfPageGeometry;
import org.apache.pdfbox.pdmodel.PDPage;

import java.util.Set;

/**
 * 页级指标：字符数、CID 缺映射率、乱码率、图片面积占比与矢量面积占比。
 */
public final class PageMetricStep implements ExtractionStep {

    /**
     * 步骤名。
     *
     * @return 固定步骤名
     */
    @Override
    public String name() {
        return "page-metric";
    }

    /**
     * 计算页级指标。
     *
     * @param context 单页提取工作区
     */
    @Override
    public void apply(ExtractionContext context) {
        PdfTokenSupport.StrippedPage stripped = context.stripped();
        PDPage page = context.document().getPage(context.pageNo() - 1);
        PdfPageGeometry geometry = PdfPageGeometry.of(page);
        double pageWidth = geometry.textWidth();
        double pageHeight = geometry.textHeight();
        context.setGeometry(geometry);

        PageMetric metric = new PageMetric();
        metric.setPage(context.pageNo());
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
        double unreadableRatio = ratio(nonCommon, nonBlank);
        double printableRatio = ratio(printableMojibake, nonBlank);
        ParseProperties properties = context.parseContext().getProperties();
        metric.setGarbledRatio(printableRatio >= properties.getPrintableGarbledRateThreshold()
                ? Math.max(unreadableRatio, printableRatio) : unreadableRatio);
        RuleSegments.Graphics graphics = RuleSegments.collect(page, pageWidth, pageHeight);
        metric.setImageAreaRatio(RuleSegments.imageAreaRatio(graphics.imageBoxes(), pageWidth, pageHeight));
        metric.setVectorAreaRatio(RuleSegments.vectorAreaRatio(graphics.segments(), pageWidth, pageHeight));
        context.setMetric(metric);
        context.setGraphics(graphics);
    }

    /** 占比（分母为 0 时返回 0） */
    private static double ratio(int count, int total) {
        return total > 0 ? (double) count / total : 0;
    }
}
