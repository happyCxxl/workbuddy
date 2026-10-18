package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.layout.ProjectionPageLayoutAnalyzer;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 跨页续表单测：下一页重复表头时，该表标为续表（{@code headerRepeated=true}）；首表不标。
 */
class PdfTableContinuationTest {

    private final PdfBoxDocumentParser parser =
            new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer());

    @Test
    void repeatedHeaderShouldMarkContinuationTable() throws Exception {
        List<ParseElement> tables = tables(parse(twoPages()));

        assertEquals(2, tables.size());
        assertNull(tables.getFirst().getHeaderRepeated());
        assertEquals(Boolean.TRUE, tables.getLast().getHeaderRepeated());
    }

    private List<ParseElement> tables(ParseSource source) {
        return source.getElements().stream().filter(element -> "TABLE".equals(element.getType())).toList();
    }

    private ParseSource parse(byte[] data) {
        ParseContext context = new ParseContext();
        context.setProperties(new ParseProperties());
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-continuation");
        fileRef.setFileName("continuation.pdf");
        fileRef.setMimeType("application/pdf");
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        return parser.parse(context);
    }

    /** 两页：页 1 表头 + 三行，页 2 重复表头 + 两行（同列同宽） */
    private byte[] twoPages() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            page(doc, new String[][] {{"Item", "Amount"}, {"A-100", "12.50"}, {"B-200", "8.00"}, {"C-300", "24.75"}});
            page(doc, new String[][] {{"Item", "Amount"}, {"D-400", "3.20"}, {"E-500", "6.40"}});
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private void page(PDDocument doc, String[][] rows) throws Exception {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            for (int i = 0; i < rows.length; i++) {
                double y = 720 - i * 18;
                cell(cs, font, 60, y, rows[i][0]);
                cell(cs, font, 430, y, rows[i][1]);
            }
        }
    }

    private void cell(PDPageContentStream cs, PDType1Font font, double x, double y, String value) throws Exception {
        cs.beginText();
        cs.setFont(font, 10);
        cs.newLineAtOffset((float) x, (float) y);
        cs.showText(value);
        cs.endText();
    }
}
