package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.capability.TableRegion;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.extract.LineTexts;
import com.workbuddy.worker.parser.pdf.extract.PdfPageExtractor;
import com.workbuddy.worker.parser.pdf.layout.ProjectionPageLayoutAnalyzer;
import com.workbuddy.worker.parser.pdf.model.PageContent;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败表 → 请求区域单测：页码、视口包围盒与块内文本按产物口径装进 TableRegion；空块返回 null。
 */
class TableRegionSupportTest {

    /** 行块转区域：页码与文本齐全、包围盒为正（{@code linesShouldBecomeRegionWithPageBboxAndText}）。 */
    @Test
    void linesShouldBecomeRegionWithPageBboxAndText() throws Exception {
        ParseContext context = context(buildTwoLinePdf());
        try (PDDocument doc = Loader.loadPDF(readAll(context))) {
            PageContent page = new PdfPageExtractor(new ProjectionPageLayoutAnalyzer())
                    .extractPage(doc, 1, context);

            TableRegion region = TableRegionSupport.of(page.lines(), page, textOptions(context));

            assertNotNull(region);
            assertEquals(1, region.getPage());
            assertEquals("page1-table-region", region.getImageRef());
            assertTrue(region.getBbox().getWidth() > 0 && region.getBbox().getHeight() > 0,
                    () -> "bbox=" + region.getBbox());
            assertTrue(region.getOcrText().contains("Score"), () -> "text=" + region.getOcrText());
            assertTrue(region.getOcrText().contains("30"), () -> "text=" + region.getOcrText());
        }
    }

    /** 空块：返回 null（不发起调用）（{@code emptyBlockShouldReturnNull}）。 */
    @Test
    void emptyBlockShouldReturnNull() throws Exception {
        ParseContext context = context(buildTwoLinePdf());
        try (PDDocument doc = Loader.loadPDF(readAll(context))) {
            PageContent page = new PdfPageExtractor(new ProjectionPageLayoutAnalyzer())
                    .extractPage(doc, 1, context);

            assertNull(TableRegionSupport.of(java.util.List.of(), page, textOptions(context)));
        }
    }

    private LineTexts.Options textOptions(ParseContext context) {
        return LineTexts.Options.of(context.getProperties());
    }

    private ParseContext context(byte[] data) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("region.pdf");
        fileRef.setMimeType("application/pdf");
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(new ParseProperties());
        return context;
    }

    private byte[] readAll(ParseContext context) throws Exception {
        return context.getInputStream().readAllBytes();
    }

    /** 造一页两行文本（当作判不出结构的表格块） */
    private byte[] buildTwoLinePdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.beginText();
                cs.newLineAtOffset(72, 700);
                cs.showText("Score 30");
                cs.endText();
                cs.beginText();
                cs.newLineAtOffset(72, 680);
                cs.showText("A1 40");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
