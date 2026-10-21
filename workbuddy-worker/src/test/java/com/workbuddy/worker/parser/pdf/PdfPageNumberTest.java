package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ElementType;
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

import com.workbuddy.worker.testkit.PropertiesFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页码判定的内容空洞度门限：贴正文的页底数字行按正文处理，与正文留出空白的才按页码。
 */
class PdfPageNumberTest {

    private final PdfBoxDocumentParser parser =
            new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer());

    /** 页底数字行紧贴上方正文（与正文间无空白）→ 留在正文，不判页码（{@code bottomNumberTouchingBodyShouldStayInBody}）。 */
    @Test
    void bottomNumberTouchingBodyShouldStayInBody() throws Exception {
        ParseSource source = parser.parse(context(buildBodyTouchingNumbers(), PropertiesFixtures.parse()));

        assertEquals(List.of(), footerTexts(source), () -> "footers=" + footerTexts(source));
        assertTrue(bodyTexts(source).stream().anyMatch(text -> text.contains("2024")),
                () -> "paragraphs=" + bodyTexts(source));
        assertTrue(bodyTexts(source).stream().anyMatch(text -> text.contains("2025")),
                () -> "paragraphs=" + bodyTexts(source));
    }

    /** 页底数字行与上方正文留出空白（孤立在页脚区）→ 判页码（{@code isolatedBottomNumberShouldBePageNumber}）。 */
    @Test
    void isolatedBottomNumberShouldBePageNumber() throws Exception {
        ParseSource source = parser.parse(context(buildIsolatedNumbers(), PropertiesFixtures.parse()));

        assertEquals(List.of("1", "2"), footerTexts(source), () -> "footers=" + footerTexts(source));
    }

    /** 空洞度比例上收到配置：比例放大后同一份文档不再判页码（{@code isolationRatioShouldComeFromProperties}）。 */
    @Test
    void isolationRatioShouldComeFromProperties() throws Exception {
        ParseProperties properties = PropertiesFixtures.parse();
        properties.setPageNumberIsolationRatio(100.0);

        ParseSource source = parser.parse(context(buildIsolatedNumbers(), properties));

        assertEquals(List.of(), footerTexts(source), () -> "footers=" + footerTexts(source));
        assertTrue(bodyTexts(source).stream().anyMatch(text -> text.contains("1")),
                () -> "paragraphs=" + bodyTexts(source));
    }

    private List<String> footerTexts(ParseSource source) {
        return source.getElements().stream()
                .filter(element -> ElementType.FOOTER.name().equals(element.getType()))
                .map(ParseElement::getText).toList();
    }

    private List<String> bodyTexts(ParseSource source) {
        return source.getElements().stream()
                .filter(element -> ElementType.PARAGRAPH.name().equals(element.getType()))
                .map(ParseElement::getText).toList();
    }

    private ParseContext context(byte[] data, ParseProperties properties) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("page-number.pdf");
        fileRef.setMimeType("application/pdf");
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(properties);
        return context;
    }

    /** 造两页：页底正文行 + 紧贴其下的数字行（两页同址，数字各不相同） */
    private byte[] buildBodyTouchingNumbers() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int page = 0; page < 2; page++) {
                PDPage pdfPage = new PDPage(new PDRectangle(595, 842));
                doc.addPage(pdfPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdfPage)) {
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    cs.beginText();
                    cs.newLineAtOffset(72, 52);
                    cs.showText(page == 0 ? "Amount alpha" : "Amount beta");
                    cs.endText();
                    cs.beginText();
                    cs.newLineAtOffset(72, 40);
                    cs.showText(String.valueOf(2024 + page));
                    cs.endText();
                }
            }
            return save(doc);
        }
    }

    /** 造两页：正文在页中，页码孤立在页底并跨页同址 */
    private byte[] buildIsolatedNumbers() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int page = 0; page < 2; page++) {
                PDPage pdfPage = new PDPage(new PDRectangle(595, 842));
                doc.addPage(pdfPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdfPage)) {
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    cs.beginText();
                    cs.newLineAtOffset(72, 400);
                    cs.showText("Body text of page " + page);
                    cs.endText();
                    cs.beginText();
                    cs.newLineAtOffset(100, 18);
                    cs.showText(String.valueOf(page + 1));
                    cs.endText();
                }
            }
            return save(doc);
        }
    }

    private byte[] save(PDDocument doc) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }
}
