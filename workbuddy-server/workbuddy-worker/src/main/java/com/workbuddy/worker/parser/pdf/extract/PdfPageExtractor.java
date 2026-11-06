package com.workbuddy.worker.parser.pdf.extract;

import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.pdf.extract.pipeline.ExtractionContext;
import com.workbuddy.worker.parser.pdf.extract.pipeline.ExtractionStep;
import com.workbuddy.worker.parser.pdf.extract.pipeline.LineBuildStep;
import com.workbuddy.worker.parser.pdf.extract.pipeline.PageContentStep;
import com.workbuddy.worker.parser.pdf.extract.pipeline.PageMetricStep;
import com.workbuddy.worker.parser.pdf.extract.pipeline.TokenExtractStep;
import com.workbuddy.worker.parser.pdf.layout.PageLayoutAnalyzer;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.util.List;

/**
 * 单页提取流水线：token → 页指标 → 行构建 → 页内容组装；PDFBox 依赖只在前两步。
 */
public class PdfPageExtractor {

    private final List<ExtractionStep> steps;

    /**
     * 构造页提取器。
     *
     * @param layoutAnalyzer 版面分析器
     */
    public PdfPageExtractor(PageLayoutAnalyzer layoutAnalyzer) {
        this.steps = List.of(
                new TokenExtractStep(),
                new PageMetricStep(),
                new LineBuildStep(layoutAnalyzer),
                new PageContentStep());
    }

    /**
     * 提取一页。
     *
     * @param doc PDF 文档
     * @param pageNo 页码（从 1 开始）
     * @param context 解析上下文
     * @return 单页提取内容
     * @throws IOException 读取页面内容失败
     */
    public PageContent extractPage(PDDocument doc, int pageNo, ParseContext context) throws IOException {
        ExtractionContext extraction = new ExtractionContext(doc, pageNo, context);
        for (ExtractionStep step : steps) {
            step.apply(extraction);
        }
        return extraction.content();
    }
}
