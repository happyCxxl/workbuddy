package com.workbuddy.worker.parser.pdf.pipeline;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.ParseUnitResult;
import com.workbuddy.common.domain.parse.capability.LayoutResult;
import com.workbuddy.common.domain.parse.capability.PageImage;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.LayoutProvider;
import com.workbuddy.worker.parser.capability.StructuredOcrProvider;
import com.workbuddy.worker.parser.capability.TableStructureProvider;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.PdfPageGeometry;
import com.workbuddy.worker.parser.pdf.model.Token;
import com.workbuddy.worker.parser.pdf.store.PageArtifactStore;
import com.workbuddy.worker.testkit.PageUnitFixtures;
import com.workbuddy.worker.testkit.PropertiesFixtures;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 逐页装配并发：页级工作并行执行、回填仍按页序；存在并发上限，不会一次打满整个窗口。
 */
class PageAssemblyConcurrencyTest {

    /** 三页并发：三页同时进入版面调用（互等栅栏放行），元素与事实仍按页序回填（{@code pagesShouldAssembleInParallelAndMergeInPageOrder}）。 */
    @Test
    void pagesShouldAssembleInParallelAndMergeInPageOrder() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        CountDownLatch arrived = new CountDownLatch(3);
        LayoutProvider provider = blockingProvider(inFlight, maxInFlight, arrived, 3000);

        ParseSource source = assemble(3, 3, provider);

        assertEquals(3, maxInFlight.get(), () -> "maxInFlight=" + maxInFlight.get());
        assertEquals(List.of(1, 2, 3), elementPages(source));
        List<Integer> factPages = source.getFacts().stream().map(fact -> pageOf(fact.getRegion())).toList();
        assertEquals(List.of(1, 2, 3), factPages.stream().distinct().toList(), () -> "factPages=" + factPages);
        assertEquals(factPages.stream().sorted().toList(), factPages, () -> "事实未按页序回填：" + factPages);
    }

    /** 并发上限为 1：页间串行，同时在飞的版面调用不超过 1 个（{@code pageConcurrencyShouldBoundInFlightPages}）。 */
    @Test
    void pageConcurrencyShouldBoundInFlightPages() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        CountDownLatch arrived = new CountDownLatch(2);
        LayoutProvider provider = blockingProvider(inFlight, maxInFlight, arrived, 120);

        assemble(2, 1, provider);

        assertEquals(1, maxInFlight.get(), () -> "maxInFlight=" + maxInFlight.get());
        assertEquals(0, arrived.getCount(), "第一页的栅栏等待应按超时放行，不得死锁");
    }

    /** 版面 provider：记录在飞峰值，进页后等栅栏（并发时同时到齐，串行时靠超时放行）。 */
    private static LayoutProvider blockingProvider(AtomicInteger inFlight, AtomicInteger maxInFlight,
                                                   CountDownLatch arrived, long waitMillis) {
        return new LayoutProvider() {
            @Override
            public LayoutResult analyze(PageImage page, ProviderContext ctx) {
                maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                arrived.countDown();
                try {
                    arrived.await(waitMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                }
                return null;
            }
        };
    }

    /** 装配若干页：每页两条跨栏行，触发版面调用；版面不可用时按几何口径降级。 */
    private static ParseSource assemble(int pageCount, int concurrency, LayoutProvider provider) throws IOException {
        ParseProperties properties = PropertiesFixtures.parse();
        properties.setPageWindowSize(pageCount);
        properties.setPageConcurrency(concurrency);

        List<PageContent> pages = new ArrayList<>();
        List<ParseUnitResult> units = new ArrayList<>();
        for (int pageNo = 1; pageNo <= pageCount; pageNo++) {
            PageContent page = page(pageNo);
            pages.add(page);
            units.add(PageUnitFixtures.of(page));
        }
        PageArtifactStore store = PageArtifactStore.open(pageCount, pageCount);
        store.writeWindow(1, pages);

        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("p.pdf");
        fileRef.setMimeType("application/pdf");
        context.setFileRef(fileRef);
        context.setProperties(properties);

        ParseSource source = ParseSource.nativeSource("pdfbox-3.4.0");
        try (PDDocument document = new PDDocument();
             ExtractedDocument extracted = new ExtractedDocument(store, units);
             LoadedPdf loaded = new LoadedPdf(document, null, pageCount)) {
            new PageAssemblyStep(registry(provider))
                    .execute(new PageAssemblyStep.Input(extracted, loaded, context, source));
        }
        return source;
    }

    /** 事实区域名转页码（区域名形如 page 3）。 */
    private static int pageOf(String region) {
        return Integer.parseInt(region.replace("page ", ""));
    }

    /** 页面元素所属页码（按回填顺序）。 */
    private static List<Integer> elementPages(ParseSource source) {
        return source.getElements().stream().map(ParseElement::getPage).toList();
    }

    /** 单页内容：两条行内带大间隙的行（跨栏行门槛 12.0 × 4.0 = 48pt）。 */
    private static PageContent page(int pageNo) {
        List<PageLine> lines = List.of(spanningLine(pageNo, 100), spanningLine(pageNo, 140));
        return new PageContent(pageNo, 595, 842, new PdfPageGeometry(595, 842, 0), new PageMetric(),
                lines, List.of(), List.of());
    }

    /** 跨栏行：两个 token 间距 180pt。 */
    private static PageLine spanningLine(int pageNo, double y) {
        return new PageLine(pageNo, 100, y, 400, 10, "left right", 10, "Helvetica", false,
                List.of(new Token("left", 100, 120), new Token("right", 300, 320)), List.of(), false);
    }

    /** 只注册版面 provider 的能力注册表。 */
    private static CapabilityRegistry registry(LayoutProvider provider) {
        return new CapabilityRegistry() {
            @Override
            public StructuredOcrProvider ocr() {
                return null;
            }

            @Override
            public LayoutProvider layout() {
                return provider;
            }

            @Override
            public TableStructureProvider table() {
                return null;
            }
        };
    }
}
