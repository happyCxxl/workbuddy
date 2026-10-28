package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.capability.LayoutRegion;
import com.workbuddy.common.domain.parse.capability.LayoutResult;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.LayoutProvider;
import com.workbuddy.worker.parser.capability.StructuredOcrProvider;
import com.workbuddy.worker.parser.capability.TableStructureProvider;
import com.workbuddy.worker.parser.pdf.assemble.LayoutComplexity;
import com.workbuddy.worker.parser.pdf.extract.PdfPageExtractor;
import com.workbuddy.worker.parser.pdf.layout.ProjectionPageLayoutAnalyzer;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import com.workbuddy.worker.testkit.PropertiesFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复杂页判定与版面能力接线的端到端单测：三栏未拆开的页（行内跨栏间隙）判为复杂页，
 * 解析时确实去问了模型并记下能力引用与补强事实；未接入能力时两样都不留痕。
 */
class PdfLayoutCapabilityTraceTest {

    /** 真实提取出的行：两行各含跨栏间隙 → 判复杂页（{@code spanningLinesShouldBeTreatedAsComplexPage}）。 */
    @Test
    void spanningLinesShouldBeTreatedAsComplexPage() throws Exception {
        ParseContext context = context();
        try (PDDocument doc = Loader.loadPDF(context.getInputStream().readAllBytes())) {
            PageContent page = new PdfPageExtractor(new ProjectionPageLayoutAnalyzer())
                    .extractPage(doc, 1, context);
            List<Double> starts = page.lines().stream().map(PageLine::x).toList();

            assertEquals(2, LayoutComplexity.spanningLineCount(page.lines(), PropertiesFixtures.parse()),
                    () -> "lines=" + page.lines().size() + " starts=" + starts);
            assertTrue(LayoutComplexity.isComplex(page.lines(), PropertiesFixtures.parse()),
                    () -> "lines=" + page.lines().size() + " starts=" + starts);
        }
    }

    /** 已接入能力：复杂页真的调了模型，产物路由记下 layout 能力引用与补强事实（{@code complexPageShouldCallCapabilityAndTraceIt}）。 */
    @Test
    void complexPageShouldCallCapabilityAndTraceIt() {
        ParseSource source = new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer(), stubRegistry())
                .parse(context());

        assertEquals(1, source.getCapabilities().size(), () -> "capabilities=" + source.getCapabilities());
        assertEquals(CapabilityUsage.KIND_LAYOUT, source.getCapabilities().getFirst().kind());
        assertEquals("stub-layout", source.getCapabilities().getFirst().model());
        assertTrue(source.getFacts().stream().anyMatch(fact -> fact.getEvidence().contains("能力补强")),
                () -> "facts=" + source.getFacts());
    }

    /** 未接入能力：不产生能力引用与补强事实（{@code withoutCapabilityShouldNotTraceAnything}）。 */
    @Test
    void withoutCapabilityShouldNotTraceAnything() {
        ParseSource source = new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer(), null)
                .parse(context());

        assertTrue(source.getCapabilities().isEmpty());
        assertFalse(source.getFacts().stream().anyMatch(fact -> fact.getEvidence().contains("能力补强")),
                () -> "facts=" + source.getFacts());
    }

    private ParseContext context() {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("layout-capability.pdf");
        fileRef.setMimeType("application/pdf");
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(buildThreeColumnPdf()));
        context.setProperties(PropertiesFixtures.parse());
        return context;
    }

    /** 只接版面能力的注册表：三栏区域 + 反向阅读顺序 */
    private CapabilityRegistry stubRegistry() {
        LayoutResult result = new LayoutResult();
        result.setModel("stub-layout");
        result.setVersion("2.1");
        result.setOrder(List.of(2, 1, 0));
        result.setRegions(List.of(column(48, 216), column(276, 216), column(504, 216)));
        return new CapabilityRegistry() {
            @Override
            public StructuredOcrProvider ocr() {
                return null;
            }

            @Override
            public LayoutProvider layout() {
                return (page, ctx) -> result;
            }

            @Override
            public TableStructureProvider table() {
                return null;
            }
        };
    }

    private LayoutRegion column(double x, double width) {
        LayoutRegion region = new LayoutRegion();
        region.setType("text");
        region.setBbox(new BBox(x, 0, width, 842));
        return region;
    }

    /** 造一页三栏、每栏两行（同一 y 上并排三栏，提取时不会被拆开） */
    private byte[] buildThreeColumnPdf() {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                write(cs, 72, 700, "alpha one");
                write(cs, 72, 680, "alpha two");
                write(cs, 300, 700, "beta one");
                write(cs, 300, 680, "beta two");
                write(cs, 520, 700, "gamma one");
                write(cs, 520, 680, "gamma two");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void write(PDPageContentStream cs, float x, float y, String text) throws Exception {
        cs.beginText();
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }
}
