package com.workbuddy.worker.parser.pdf.pipeline;

import com.workbuddy.common.domain.parse.PageDimension;
import com.workbuddy.common.domain.parse.ParseUnitResult;
import com.workbuddy.common.domain.parse.UnitLine;
import com.workbuddy.common.enums.parse.UnitType;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.extract.PdfPageExtractor;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
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

            List<ParseUnitResult> units = new ArrayList<>(pageCount);
            List<PageContent> window = new ArrayList<>(windowSize);
            for (int pageNo = 1; pageNo <= pageCount; pageNo++) {
                context.getCancellationToken().throwIfCancelled();
                PageContent pageContent = pageExtractor.extractPage(loaded.document(), pageNo, context);
                units.add(toUnit(pageContent));
                window.add(pageContent);
                if (window.size() == windowSize || pageNo == pageCount) {
                    store.writeWindow(pageNo - window.size() + 1, window);
                    window = new ArrayList<>(windowSize);
                }
            }
            return new ExtractedDocument(store, units);
        } catch (IOException | RuntimeException e) {
            store.close();
            throw e;
        }
    }

    /** 页提取内容 → 单元结果（行摘要 + 指标 + 页尺寸；元素在装配阶段回填） */
    private static ParseUnitResult toUnit(PageContent page) {
        ParseUnitResult unit = new ParseUnitResult();
        unit.setUnitIndex(page.pageNo());
        unit.setType(UnitType.PAGE);
        unit.setMetric(page.metric());
        unit.setDimension(new PageDimension(page.pageNo(),
                page.geometry().viewportWidth(), page.geometry().viewportHeight()));
        unit.setLayoutHeight(page.pageHeight());
        List<UnitLine> lines = new ArrayList<>(page.lines().size());
        for (PageLine line : page.lines()) {
            lines.add(new UnitLine(line.text(), line.x(), line.y(), line.width(), line.height()));
        }
        unit.setLines(lines);
        return unit;
    }
}
