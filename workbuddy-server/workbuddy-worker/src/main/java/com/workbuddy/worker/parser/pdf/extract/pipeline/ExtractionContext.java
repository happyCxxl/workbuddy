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

    /**
     * 返回所属 PDF 文档。
     *
     * @return PDF 文档
     */
    public PDDocument document() {
        return document;
    }

    /**
     * 返回页码。
     *
     * @return 页码
     */
    public int pageNo() {
        return pageNo;
    }

    /**
     * 返回解析环节上下文。
     *
     * @return 解析上下文
     */
    public ParseContext parseContext() {
        return parseContext;
    }

    /**
     * 返回脱敏后的页 token。
     *
     * @return 脱敏页 token
     */
    public PdfTokenSupport.StrippedPage stripped() {
        return stripped;
    }

    /**
     * 设置脱敏后的页 token。
     *
     * @param stripped 脱敏页 token
     */
    public void setStripped(PdfTokenSupport.StrippedPage stripped) {
        this.stripped = stripped;
    }

    /**
     * 返回页几何。
     *
     * @return 页几何
     */
    public PdfPageGeometry geometry() {
        return geometry;
    }

    /**
     * 设置页几何。
     *
     * @param geometry 页几何
     */
    public void setGeometry(PdfPageGeometry geometry) {
        this.geometry = geometry;
    }

    /**
     * 返回页级指标。
     *
     * @return 页级指标
     */
    public PageMetric metric() {
        return metric;
    }

    /**
     * 设置页级指标。
     *
     * @param metric 页级指标
     */
    public void setMetric(PageMetric metric) {
        this.metric = metric;
    }

    /**
     * 返回页内图形与线段。
     *
     * @return 图形与线段
     */
    public RuleSegments.Graphics graphics() {
        return graphics;
    }

    /**
     * 设置页内图形与线段。
     *
     * @param graphics 图形与线段
     */
    public void setGraphics(RuleSegments.Graphics graphics) {
        this.graphics = graphics;
    }

    /**
     * 返回页内文本行。
     *
     * @return 文本行
     */
    public List<PageLine> lines() {
        return lines;
    }

    /**
     * 设置页内文本行。
     *
     * @param lines 文本行
     */
    public void setLines(List<PageLine> lines) {
        this.lines = lines;
    }

    /**
     * 返回页内容。
     *
     * @return 页内容
     */
    public PageContent content() {
        return content;
    }

    /**
     * 设置页内容。
     *
     * @param content 页内容
     */
    public void setContent(PageContent content) {
        this.content = content;
    }
}
