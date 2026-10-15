package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.input.FileReference;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 乱码率口径：拉丁补充区（可打印乱码带）成片出现的页面按乱码计；正常中英文正文不受影响。
 *
 * @author cxxl
 */
class PdfGarbledRatioTest {

    private final PdfBoxDocumentParser parser =
            new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer());

    /** 字体映射错位出的可打印乱码（拉丁补充区成片）→ 乱码率按该带占比计 */
    @Test
    void printableMojibakeBandShouldRaiseGarbledRatio() throws Exception {
        ParseSource source = parser.parse(context(buildPage("¡¢£¤¥§¨©ª«¬®¯°±²³´µ¶·¸¹º»¼½¾¿")));

        assertTrue(source.getPageMetrics().getFirst().getGarbledRatio() >= 0.9,
                () -> "garbled=" + source.getPageMetrics().getFirst().getGarbledRatio());
    }

    /** 正常中英文正文：该带占比低于门槛，不乱判 */
    @Test
    void normalTextShouldKeepGarbledRatioLow() throws Exception {
        ParseSource source = parser.parse(context(buildPage("Bidding price 30 EUR")));

        assertEquals(0.0, source.getPageMetrics().getFirst().getGarbledRatio(), 0.0001);
        assertEquals(0.0, source.getPageMetrics().getFirst().getCidMappingRatio(), 0.0001);
    }

    private ParseContext context(byte[] data) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("garbled.pdf");
        fileRef.setMimeType("application/pdf");
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(new ParseProperties());
        return context;
    }

    /** 造一页：用 WinAnsi 编码写一行文本（拉丁补充区字符可编码） */
    private byte[] buildPage(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.beginText();
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
