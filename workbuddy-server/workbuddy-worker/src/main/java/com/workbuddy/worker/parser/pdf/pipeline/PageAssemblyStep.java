package com.workbuddy.worker.parser.pdf.pipeline;

import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.ParseUnitResult;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.pdf.asset.PdfPageImageRenderer;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.page.BlockClassifyStep;
import com.workbuddy.worker.parser.pdf.page.BlockRenderStep;
import com.workbuddy.worker.parser.pdf.page.DuplicateTableStep;
import com.workbuddy.worker.parser.pdf.page.ElementIdStep;
import com.workbuddy.worker.parser.pdf.page.ImageStep;
import com.workbuddy.worker.parser.pdf.page.PageContext;
import com.workbuddy.worker.parser.pdf.page.PagePipeline;
import com.workbuddy.worker.parser.pdf.page.ReadingOrderStep;
import com.workbuddy.worker.parser.pdf.page.RegionStep;
import com.workbuddy.worker.parser.pdf.page.TableProvenanceStep;

import java.io.IOException;
import java.util.List;

/**
 * 逐页装配步骤：按窗口读取页产物，逐页执行页面流水线，把元素按序回填进解析结果。
 */
public final class PageAssemblyStep
        implements DocumentStep<PageAssemblyStep.Input, ParseSource> {

    private final CapabilityRegistry capabilityRegistry;
    private final PagePipeline pagePipeline = new PagePipeline(List.of(
            new RegionStep(),
            new ReadingOrderStep(),
            new BlockClassifyStep(),
            new BlockRenderStep(),
            new ImageStep(),
            new DuplicateTableStep(),
            new ElementIdStep(),
            new TableProvenanceStep()));

    /**
     * 构造逐页装配步骤。
     *
     * @param capabilityRegistry 能力注册表；未接入时可为 null
     */
    public PageAssemblyStep(CapabilityRegistry capabilityRegistry) {
        this.capabilityRegistry = capabilityRegistry;
    }

    /**
     * 逐页装配输入。
     *
     * @param extracted 逐页提取结果（含单元集合）
     * @param loaded 已加载文档
     * @param context 解析上下文
     * @param source 解析结果（元素、事实与能力直接记在该结果上）
     */
    public record Input(ExtractedDocument extracted, LoadedPdf loaded, ParseContext context,
                        ParseSource source) {
    }

    /**
     * 执行逐页装配。
     *
     * @param input 装配输入
     * @return 解析结果（元素已按序回填）
     * @throws IOException 读取页产物或渲染页图失败
     */
    @Override
    public ParseSource execute(Input input) throws IOException {
        ExtractedDocument extracted = input.extracted();
        ParseContext context = input.context();
        ParseProperties properties = context.getProperties();
        String fileId = context.getFileRef().getFileId();
        int windowSize = Math.max(properties.getPageWindowSize(), 1);

        PdfPageImageRenderer renderer = context.getAssetSink().enabled()
                ? new PdfPageImageRenderer(input.loaded().document(), properties.getPageImageDpi())
                : null;

        ParseSource source = input.source();
        List<ParseUnitResult> units = extracted.units();
        for (int from = 1; from <= extracted.pageCount(); from += windowSize) {
            for (PageContent page : extracted.store().readWindow(from)) {
                context.getCancellationToken().throwIfCancelled();
                ParseUnitResult unit = units.get(page.pageNo() - 1);
                PageContext pageContext = new PageContext(page, unit, properties,
                        capabilityRegistry, context, renderer, fileId, source);
                pagePipeline.run(pageContext);
                source.getElements().addAll(pageContext.getElements());
            }
        }
        return source;
    }
}
