package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.parse.PageDimension;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.input.FileFormat;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.DocumentParserPort;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.pdf.assemble.PageElementIds;
import com.workbuddy.worker.parser.pdf.assemble.PageTableProvenance;
import com.workbuddy.worker.parser.pdf.assemble.PdfBodyAssembler;
import com.workbuddy.worker.parser.pdf.assemble.TableContinuationSupport;
import com.workbuddy.worker.parser.pdf.assemble.PdfImageEmitter;
import com.workbuddy.worker.parser.pdf.detect.HeaderFooterDetector;
import com.workbuddy.worker.parser.pdf.extract.PdfPageExtractor;
import com.workbuddy.worker.parser.pdf.layout.PageLayoutAnalyzer;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLines;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PDFBox 原生解析器（数字 PDF）：编排三段 —— 逐页提取（{@link PdfPageExtractor}）→
 * 页眉页脚识别（{@link HeaderFooterDetector}）→ 逐页装配元素（{@link PdfBodyAssembler} +
 * {@link RuledTableBuilder}/{@link TextTableBuilder} + {@link PdfImageEmitter}）。
 */
@Slf4j
@Component
public class PdfBoxDocumentParser implements DocumentParserPort {

    private static final String CAPABILITY = "pdfbox";
    private static final String CAPABILITY_VERSION = "3.0.4";

    private final PdfPageExtractor pageExtractor;
    private final CapabilityRegistry capabilityRegistry;

    /**
     * 只跑规则路径（不接模型能力）的构造器。
     *
     * @param layoutAnalyzer 版面分析器
     */
    public PdfBoxDocumentParser(PageLayoutAnalyzer layoutAnalyzer) {
        this(layoutAnalyzer, null);
    }

    /**
     * 运行期构造器：注入能力注册表（未开启或未接入时注册表返回 null）。
     *
     * @param layoutAnalyzer     版面分析器
     * @param capabilityRegistry 模型能力注册表（可空）
     */
    @Autowired
    public PdfBoxDocumentParser(PageLayoutAnalyzer layoutAnalyzer, CapabilityRegistry capabilityRegistry) {
        this.pageExtractor = new PdfPageExtractor(layoutAnalyzer);
        this.capabilityRegistry = capabilityRegistry;
    }

    /**
     * 是否支持该媒体类型。
     *
     * @param mimeType 媒体类型
     * @return 支持 PDF 为 true
     */
    @Override
    public boolean supports(String mimeType) {
        return FileFormat.PDF.getMimeType().equals(mimeType);
    }

    /**
     * 能力名。
     *
     * @return 能力标识
     */
    @Override
    public String capabilityName() {
        return CAPABILITY;
    }

    /**
     * 能力版本。
     *
     * @return 能力版本号
     */
    @Override
    public String capabilityVersion() {
        return CAPABILITY_VERSION;
    }

    /**
     * 解析入口：大文件落临时文件按页读取，逐页提取后交给组装器。
     *
     * @param context 解析上下文
     * @return 解析结果
     */
    @Override
    public ParseSource parse(ParseContext context) {
        String fileId = context.getFileRef().getFileId();
        ParseSource source = ParseSource.nativeSource(CAPABILITY + "-" + CAPABILITY_VERSION);
        Path tempFile = null;
        try {
            // 输入：小文件整档留内存；大文件落临时文件后按需读取（PDFBox 按页取对象，不整档驻留）
            int largeFileMinBytes = context.getProperties().getLargeFileMinBytes();
            int probe = largeFileMinBytes >= Integer.MAX_VALUE ? Integer.MAX_VALUE : largeFileMinBytes + 1;
            byte[] head = context.getInputStream().readNBytes(probe);
            PDDocument doc;
            if (head.length <= largeFileMinBytes) {
                doc = Loader.loadPDF(head);
            } else {
                tempFile = spoolToTempFile(context, head);
                doc = Loader.loadPDF(tempFile.toFile(), IOUtils.createTempFileOnlyStreamCache());
            }
            try (PDDocument document = doc) {
                parseDocument(document, source, context, fileId);
            }
        } catch (Exception e) {
            log.warn("===> PdfBoxDocumentParser PDF 解析失败, fileId={}", fileId, e);
            throw new IllegalStateException("PDF 解析失败: " + e.getMessage(), e);
        } finally {
            deleteQuietly(tempFile);
        }
        return source;
    }

    /** 逐页提取 + 跨页识别 + 分窗装配（窗口大小 = `pageWindowSize`，全档只留行级轻量摘要） */
    private void parseDocument(PDDocument doc, ParseSource source, ParseContext context, String fileId)
            throws IOException {
        ParseProperties properties = context.getProperties();
        int pageCount = doc.getNumberOfPages();
        source.setUnitCount(pageCount);
        context.getFileRef().setPageCount(pageCount);

        // ① 全档轻量行摘要 + 页级指标：跨页页眉页脚识别需要全档视野
        int windowSize = Math.max(properties.getPageWindowSize(), 1);
        boolean singleWindow = pageCount <= windowSize;
        List<PageLines> pageLines = new ArrayList<>();
        List<PageContent> cached = singleWindow ? new ArrayList<>() : null;
        for (int p = 1; p <= pageCount; p++) {
            PageContent pageContent = pageExtractor.extractPage(doc, p, context);
            pageLines.add(PageLines.of(pageContent));
            source.getPageMetrics().add(pageContent.metric());
            source.getPageDimensions().add(new PageDimension(pageContent.pageNo(),
                    pageContent.geometry().viewportWidth(), pageContent.geometry().viewportHeight()));
            if (singleWindow) {
                cached.add(pageContent);
            }
        }

        // ② HEADER/FOOTER 识别：页顶/页底区域内跨页重复文本与页码模式（先算，正文聚合时排除）
        Set<String> headers = HeaderFooterDetector.detectHeaders(pageLines, context);
        HeaderFooterDetector.FooterKeys footers = HeaderFooterDetector.detectFooters(pageLines, context);

        // ③ 分窗装配元素：页眉页脚 → 正文（段落与两条表格路径）→ 页内图片 → 本页补元素 ID 与表格溯源
        List<ParseElement> elements = new ArrayList<>();
        for (int from = 1; from <= pageCount; from += windowSize) {
            List<PageContent> window = singleWindow
                    ? cached
                    : extractWindow(doc, from, Math.min(from + windowSize - 1, pageCount), context);
            for (PageContent page : window) {
                int mark = elements.size();
                PdfBodyAssembler.assemble(page, headers, footers, elements, source, fileId, properties,
                        capabilityRegistry);
                PdfImageEmitter.emit(page, elements, source, fileId, properties);
                List<ParseElement> pageElements = elements.subList(mark, elements.size());
                PageElementIds.assign(pageElements, page.pageNo());
                PageTableProvenance.assign(pageElements, fileId, page.pageNo());
            }
        }
        source.setElements(elements);
        TableContinuationSupport.markContinuedTables(elements, properties.getTableContinuationOverlapRatio());
    }

    /** 窗口内逐页提取（窗口外的重数据不保留） */
    private List<PageContent> extractWindow(PDDocument doc, int from, int to, ParseContext context)
            throws IOException {
        List<PageContent> window = new ArrayList<>();
        for (int p = from; p <= to; p++) {
            window.add(pageExtractor.extractPage(doc, p, context));
        }
        return window;
    }

    /** 已读入的头部再加流剩余部分落到临时文件 */
    private static Path spoolToTempFile(ParseContext context, byte[] head) throws IOException {
        Path path = Files.createTempFile("kb-parse-", ".pdf");
        try (OutputStream out = Files.newOutputStream(path)) {
            out.write(head);
            context.getInputStream().transferTo(out);
        }
        return path;
    }

    /** 临时文件清理：失败不掩盖解析异常 */
    private static void deleteQuietly(Path path) {
        if (NullUtil.isNull(path)) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("===> PdfBoxDocumentParser 解析临时文件删除失败, path={}", path, e);
        }
    }
}
