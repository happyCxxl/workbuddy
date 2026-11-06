package com.workbuddy.worker.parser.pdf.extract.pipeline;

import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.pdf.detect.RuleSegments;
import com.workbuddy.worker.parser.pdf.extract.PdfTokenSupport;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.PdfPageGeometry;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.util.List;

/**
 * 单页提取工作区：token、几何、指标、行与最终页内容在同一处传递，步骤只读写本层字段。
 */
public final class ExtractionContext {

    private final PDDocument document;
    private final int pageNo;
    private final ParseContext parseContext;

    private PdfTokenSupport.StrippedPage stripped;
    private PdfPageGeometry geometry;
    private PageMetric metric;
    private RuleSegments.Graphics graphics;
    private List<PageLine> lines;
    private PageContent content;

    /**
     * 构造单页提取工作区。
     *
     * @param document 文档
     * @param pageNo 页码（从 1 开始）
     * @param parseContext 解析上下文
     */
    public ExtractionContext(PDDocument document, int pageNo, ParseContext parseContext) {
        this.document = document;
        this.pageNo = pageNo;
        this.parseContext = parseContext;
    }

    public PDDocument document() {
        return document;
    }

    public int pageNo() {
        return pageNo;
    }

    public ParseContext parseContext() {
        return parseContext;
    }

    public PdfTokenSupport.StrippedPage stripped() {
        return stripped;
    }

    public void setStripped(PdfTokenSupport.StrippedPage stripped) {
        this.stripped = stripped;
    }

    public PdfPageGeometry geometry() {
        return geometry;
    }

    public void setGeometry(PdfPageGeometry geometry) {
        this.geometry = geometry;
    }

    public PageMetric metric() {
        return metric;
    }

    public void setMetric(PageMetric metric) {
        this.metric = metric;
    }

    public RuleSegments.Graphics graphics() {
        return graphics;
    }

    public void setGraphics(RuleSegments.Graphics graphics) {
        this.graphics = graphics;
    }

    public List<PageLine> lines() {
        return lines;
    }

    public void setLines(List<PageLine> lines) {
        this.lines = lines;
    }

    public PageContent content() {
        return content;
    }

    public void setContent(PageContent content) {
        this.content = content;
    }
}
