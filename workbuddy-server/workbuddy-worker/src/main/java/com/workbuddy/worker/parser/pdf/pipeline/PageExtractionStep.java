package com.workbuddy.worker.parser.pdf.pipeline;

import com.workbuddy.common.domain.parse.PageDimension;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.extract.PdfPageExtractor;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLines;
import com.workbuddy.worker.parser.pdf.store.PageArtifactStore;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 逐页提取步骤：每页只提取一次，行摘要/指标/尺寸留全档，页产物按窗口写入存储。
 */
@Slf4j
public final class PageExtractionStep implements DocumentStep<PageExtractionStep.Input, ExtractedDocument> {

    private final PdfPageExtractor pageExtractor;

    /**
     * 构造逐页提取步骤。
     *
     * @param pageExtractor 页提取器
     */
    public PageExtractionStep(PdfPageExtractor pageExtractor) {
        this.pageExtractor = pageExtractor;
    }

    /**
     * 逐页提取输入。
     *
     * @param loaded 已加载文档
     * @param context 解析上下文
     */
    public record Input(LoadedPdf loaded, ParseContext context) {
    }

    /**
     * 执行逐页提取。
     *
     * @param input 提取输入
     * @return 提取结果
     * @throws IOException 提取页内容或写页产物失败
     */
    @Override
    public ExtractedDocument execute(Input input) throws IOException {
        LoadedPdf loaded = input.loaded();
        ParseContext context = input.context();
        ParseProperties properties = context.getProperties();
        int pageCount = loaded.pageCount();
        context.getFileRef().setPageCount(pageCount);

        int windowSize = Math.max(properties.getPageWindowSize(), 1);
        PageArtifactStore store = PageArtifactStore.open(pageCount, windowSize);
        try {
            log.info("===> PdfBoxDocumentParser 页产物存储, fileId={}, store={}, pageCount={}, windowSize={}",
                    context.getFileRef().getFileId(), store.getClass().getSimpleName(), pageCount, windowSize);

            List<PageLines> pageLines = new ArrayList<>(pageCount);
            List<PageMetric> pageMetrics = new ArrayList<>(pageCount);
            List<PageDimension> pageDimensions = new ArrayList<>(pageCount);
            List<PageContent> window = new ArrayList<>(windowSize);
            for (int pageNo = 1; pageNo <= pageCount; pageNo++) {
                context.getCancellationToken().throwIfCancelled();
                PageContent pageContent = pageExtractor.extractPage(loaded.document(), pageNo, context);
                pageLines.add(PageLines.of(pageContent));
                pageMetrics.add(pageContent.metric());
                pageDimensions.add(new PageDimension(pageContent.pageNo(),
                        pageContent.geometry().viewportWidth(), pageContent.geometry().viewportHeight()));
                window.add(pageContent);
                if (window.size() == windowSize || pageNo == pageCount) {
                    store.writeWindow(pageNo - window.size() + 1, window);
                    window = new ArrayList<>(windowSize);
                }
            }
            return new ExtractedDocument(store, pageLines, pageMetrics, pageDimensions, pageCount);
        } catch (IOException | RuntimeException e) {
            store.close();
            throw e;
        }
    }
}
