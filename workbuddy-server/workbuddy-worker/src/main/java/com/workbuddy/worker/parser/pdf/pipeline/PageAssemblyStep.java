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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * 逐页装配步骤：按窗口读取页产物，页间并发执行页面流水线，再按页序把元素、事实与能力回填进解析结果。
 * 页间并发只并行页级工作（含模型调用），回填仍在调用线程按页序完成，产物顺序与串行执行一致。
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
     * 执行逐页装配：窗口内页并发，窗口内与窗口间都按页序回填。
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
        Semaphore permits = new Semaphore(Math.max(properties.getPageConcurrency(), 1));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int from = 1; from <= extracted.pageCount(); from += windowSize) {
                List<Future<PageContext>> futures = new ArrayList<>();
                for (PageContent page : extracted.store().readWindow(from)) {
                    futures.add(executor.submit(() -> assemblePage(permits, page, units, properties,
                            context, renderer, fileId)));
                }
                appendInPageOrder(source, futures);
            }
        }
        return source;
    }

    /**
     * 装配单页：受并发许可约束；页级工作互不共享状态，合并由调用线程按页序完成。
     *
     * @param permits            并发许可
     * @param page               页内容
     * @param units              单元集合（只读）
     * @param properties         解析配置
     * @param context            解析上下文
     * @param renderer           页图渲染器；资产关闭时为 null
     * @param fileId             文件 ID
     * @return 该页的装配结果
     * @throws InterruptedException 等待并发许可时被中断
     */
    private PageContext assemblePage(Semaphore permits, PageContent page, List<ParseUnitResult> units,
                                     ParseProperties properties, ParseContext context,
                                     PdfPageImageRenderer renderer, String fileId)
            throws InterruptedException {
        context.getCancellationToken().throwIfCancelled();
        permits.acquire();
        try {
            ParseUnitResult unit = units.get(page.pageNo() - 1);
            PageContext pageContext = new PageContext(page, unit, properties,
                    capabilityRegistry, context, renderer, fileId);
            pagePipeline.run(pageContext);
            return pageContext;
        } finally {
            permits.release();
        }
    }

    /**
     * 按页序回填：等待窗口内全部页完成后再合并，保持元素、事实、能力的文档序；
     * 任一页失败则取消同窗口其余页并抛出首个异常。
     *
     * @param source  解析结果
     * @param futures 页装配结果（按页序提交）
     * @throws IOException 页装配抛出 IO 异常
     */
    private static void appendInPageOrder(ParseSource source, List<Future<PageContext>> futures) throws IOException {
        List<PageContext> pages = new ArrayList<>(futures.size());
        try {
            for (Future<PageContext> future : futures) {
                pages.add(future.get());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            futures.forEach(future -> future.cancel(true));
            throw new IllegalStateException("页装配被中断", exception);
        } catch (ExecutionException exception) {
            futures.forEach(future -> future.cancel(true));
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IllegalStateException("页装配失败", cause);
        }
        for (PageContext page : pages) {
            source.getElements().addAll(page.getElements());
            source.getFacts().addAll(page.getFacts());
            source.getCapabilities().addAll(page.getCapabilities());
        }
    }
}
