package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.capability.LayoutRegion;
import com.workbuddy.common.domain.parse.capability.LayoutResult;
import com.workbuddy.common.domain.parse.capability.PageImage;
import com.workbuddy.common.domain.parse.capability.ProviderContext;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.LayoutProvider;
import com.workbuddy.worker.parser.capability.StructuredOcrProvider;
import com.workbuddy.worker.parser.capability.TableStructureProvider;
import com.workbuddy.worker.parser.pdf.detect.HeaderFooterDetector;
import com.workbuddy.worker.parser.pdf.detect.HeaderFooterKeys;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.PdfPageGeometry;
import com.workbuddy.worker.parser.pdf.model.Segment;
import com.workbuddy.worker.parser.pdf.model.Token;
import com.workbuddy.worker.testkit.PageUnitFixtures;
import com.workbuddy.worker.testkit.PropertiesFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页面步骤契约：页眉/页脚只收带内命中行，区域按线框归组，阅读顺序无能力时不变、有能力时按模型顺序。
 */
class PageStepContractTest {

    /** 网格列线 x（两列） */
    private static final double[] COLUMNS = {74, 200, 520};

    /** 网格行线 y（两行） */
    private static final double[] ROWS = {200, 300, 380};

    /**
     * 守护 {@code headerBandShouldOnlyEmitInBand}：页眉带内命中键的行产 HEADER，正文行不产。
     */
    @Test
    void headerBandShouldOnlyEmitInBand() {
        PageContext context = context(page(List.of(
                line("Report", 100, 42, List.of()),
                line("Body text", 100, 200, List.of()))),
                new HeaderFooterKeys(Set.of("Report"),
                        new HeaderFooterDetector.FooterKeys(Set.of(), List.of())));

        new HeaderBandStep().apply(context);

        assertEquals(1, context.getElements().size(), () -> "elements=" + context.getElements());
        ParseElement header = context.getElements().getFirst();
        assertEquals(ElementType.HEADER.name(), header.getType());
        assertEquals("Report", header.getText());
        assertEquals("pdf#top-area", header.getProvenance().getPath());
    }

    /**
     * 守护 {@code footerCollectShouldOnlyStageInBand}：页底带内命中页脚键的行暂存为 FOOTER，正文行不暂存。
     */
    @Test
    void footerCollectShouldOnlyStageInBand() {
        PageContext context = context(page(List.of(
                line("Body text", 100, 700, List.of()),
                line("Footer", 100, 800, List.of()))),
                new HeaderFooterKeys(Set.of(),
                        new HeaderFooterDetector.FooterKeys(Set.of("Footer"), List.of())));

        new FooterCollectStep().apply(context);

        assertEquals(1, context.getFooterElements().size(),
                () -> "footers=" + context.getFooterElements());
        ParseElement footer = context.getFooterElements().getFirst();
        assertEquals(ElementType.FOOTER.name(), footer.getType());
        assertEquals("Footer", footer.getText());
        assertEquals("pdf#bottom-area", footer.getProvenance().getPath());
    }

    /**
     * 守护 {@code regionStepShouldGroupBodyLinesByRegion}：正文行按中心落入线框区域，
     * 区域内行按 y 排序。
     */
    @Test
    void regionStepShouldGroupBodyLinesByRegion() {
        List<PageLine> lines = new ArrayList<>();
        lines.add(line("lower", 80, 345, List.of()));
        lines.add(line("upper", 80, 305, List.of()));
        PageContext context = context(gridPage(lines),
                new HeaderFooterKeys(Set.of(), new HeaderFooterDetector.FooterKeys(Set.of(), List.of())));
        context.setBodyLines(lines);

        new RegionStep().apply(context);

        assertFalse(context.getRegions().isEmpty(), "未识别到线框区域");
        assertEquals(1, context.getRegionLines().size(), () -> "regionLines=" + context.getRegionLines());
        List<PageLine> grouped = context.getRegionLines().values().iterator().next();
        assertEquals(List.of("upper", "lower"), grouped.stream().map(PageLine::text).toList(),
                () -> "grouped=" + grouped);
    }

    /**
     * 守护 {@code readingOrderShouldKeepGeometryWithoutCapability}：无版面能力时保持输入顺序。
     */
    @Test
    void readingOrderShouldKeepGeometryWithoutCapability() {
        List<PageLine> body = List.of(line("first", 100, 100, List.of()),
                line("second", 100, 300, List.of()));
        PageContext context = context(page(body),
                new HeaderFooterKeys(Set.of(), new HeaderFooterDetector.FooterKeys(Set.of(), List.of())));
        context.setBodyLines(body);

        new ReadingOrderStep().apply(context);

        assertEquals(body, context.getOrderedLines());
    }

    /**
     * 守护 {@code readingOrderShouldUseLayoutOrderWhenAvailable}：复杂页有版面能力时按模型区域顺序重排。
     */
    @Test
    void readingOrderShouldUseLayoutOrderWhenAvailable() {
        PageLine top = line("top", 100, 100,
                List.of(new Token("left", 100, 120), new Token("right", 300, 320)));
        PageLine bottom = line("bottom", 100, 300,
                List.of(new Token("left", 100, 120), new Token("right", 300, 320)));
        PageContext context = context(page(List.of(top, bottom)),
                new HeaderFooterKeys(Set.of(), new HeaderFooterDetector.FooterKeys(Set.of(), List.of())));
        context.setBodyLines(List.of(top, bottom));
        context.getSource().setCapabilities(new ArrayList<>());

        PageContext capable = new PageContext(context.getPage(), context.getUnit(), context.getKeys(),
                properties(), layoutRegistry(), parseContext(properties()), null, "F-1",
                ParseSource.nativeSource("test"));
        capable.setBodyLines(List.of(top, bottom));
        capable.getSource().setCapabilities(new ArrayList<>());

        new ReadingOrderStep().apply(capable);

        assertEquals(List.of("bottom", "top"), capable.getOrderedLines().stream().map(PageLine::text).toList(),
                () -> "ordered=" + capable.getOrderedLines());
        assertTrue(capable.getSource().getFacts().stream()
                .anyMatch(fact -> fact.getEvidence().contains("版面")), "缺少版面补强事实");
        assertFalse(capable.getSource().getCapabilities().isEmpty(), "缺少能力引用");
    }

    /** 版面能力：两个区域，模型顺序与几何顺序相反。 */
    private static CapabilityRegistry layoutRegistry() {
        LayoutProvider provider = new LayoutProvider() {
            @Override
            public LayoutResult analyze(PageImage page, ProviderContext ctx) {
                LayoutRegion top = new LayoutRegion();
                top.setType("text");
                top.setBbox(new BBox(80, 90, 460, 30));
                LayoutRegion bottom = new LayoutRegion();
                bottom.setType("text");
                bottom.setBbox(new BBox(80, 290, 460, 30));
                LayoutResult result = new LayoutResult();
                result.setRegions(List.of(top, bottom));
                result.setOrder(List.of(1, 0));
                result.setModel("fake-layout");
                result.setVersion("1.0");
                return result;
            }
        };
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

    /** 构造页面工作区（无能力、无渲染器）。 */
    private static PageContext context(PageContent page, HeaderFooterKeys keys) {
        ParseProperties properties = properties();
        return new PageContext(page, PageUnitFixtures.of(page), keys, properties, null,
                parseContext(properties), null, "F-1", ParseSource.nativeSource("test"));
    }

    /** 造一行；token 可为空。 */
    private static PageLine line(String text, double x, double y, List<Token> tokens) {
        return new PageLine(1, x, y, Math.max(10, text.length() * 5), 10, text, 10, "Helvetica",
                false, tokens, List.of(), false);
    }

    /** 造单页内容（无线段）。 */
    private static PageContent page(List<PageLine> lines) {
        return new PageContent(1, 595, 842, new PdfPageGeometry(595, 842, 0), new PageMetric(),
                lines, List.of(), List.of());
    }

    /** 造单页内容：3 行 2 列线框网格。 */
    private static PageContent gridPage(List<PageLine> lines) {
        List<Segment> segments = new ArrayList<>();
        for (double row : ROWS) {
            segments.add(new Segment(COLUMNS[0], row, COLUMNS[COLUMNS.length - 1], row));
        }
        for (double column : COLUMNS) {
            segments.add(new Segment(column, ROWS[0], column, ROWS[ROWS.length - 1]));
        }
        return new PageContent(1, 595, 842, new PdfPageGeometry(595, 842, 0), new PageMetric(),
                lines, segments, List.of());
    }

    /** 解析阈值（真实配置面）。 */
    private static ParseProperties properties() {
        return PropertiesFixtures.parse();
    }

    /** 解析上下文：只需文件 ID 与阈值配置。 */
    private static ParseContext parseContext(ParseProperties properties) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        context.setFileRef(fileRef);
        context.setProperties(properties);
        return context;
    }
}
