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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三栏页阅读顺序与段落边界：同排的多栏文字按栏切开、段落不跨栏合并，跨栏行单独成段。
 * 样例文件见 samples/pdf-parse-06。
 */
class PdfThreeColumnTest {

    /** 三栏的横向起点（栏距 180pt） */
    private static final float[] COLUMNS = {50, 230, 410};

    private final PdfBoxDocumentParser parser =
            new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer());

    /** 同排文字（含一条溢出到邻栏、字符区间重叠的长行）→ 每栏各成一段，文本不跨栏（{@code sameRowColumnTextsShouldStayInTheirOwnParagraph}）。 */
    @Test
    void sameRowColumnTextsShouldStayInTheirOwnParagraph() throws Exception {
        ParseSource source = parser.parse(context(buildThreeColumnPage()));

        List<String> paragraphs = paragraphTexts(source);
        assertEquals(3, paragraphs.size(), () -> "paragraphs=" + paragraphs);
        assertColumnParagraph(paragraphs.get(0), "LEFT");
        assertColumnParagraph(paragraphs.get(1), "MID");
        assertColumnParagraph(paragraphs.get(2), "RIGHT");
    }

    /** 该段只含本栏文本：本栏关键词齐全，邻栏关键词一个都不出现 */
    private void assertColumnParagraph(String paragraph, String column) {
        assertTrue(paragraph.contains(column + "-LONG"), () -> "paragraph=" + paragraph);
        assertTrue(paragraph.contains(column + "-BODY-two"), () -> "paragraph=" + paragraph);
        for (String other : List.of("LEFT", "MID", "RIGHT")) {
            if (!other.equals(column)) {
                assertFalse(paragraph.contains(other + "-"), () -> "paragraph=" + paragraph);
            }
        }
    }

    private List<String> paragraphTexts(ParseSource source) {
        return source.getElements().stream()
                .filter(element -> ElementType.PARAGRAPH.name().equals(element.getType()))
                .map(ParseElement::getText).toList();
    }

    private ParseContext context(byte[] data) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("three-column.pdf");
        fileRef.setMimeType("application/pdf");
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(PropertiesFixtures.parse());
        return context;
    }

    /** 造一页三栏：五条同排行，首条各栏文本溢出并压到邻栏字符区间上 */
    private byte[] buildThreeColumnPage() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                writeRow(cs, 740, longText("LEFT"), longText("MID"), longText("RIGHT"));
                writeRow(cs, 720, shortText("LEFT", "SHORT"), shortText("MID", "SHORT"),
                        shortText("RIGHT", "SHORT"));
                writeRow(cs, 700, shortText("LEFT", "BODY-one"), shortText("MID", "BODY-one"),
                        shortText("RIGHT", "BODY-one"));
                writeRow(cs, 680, shortText("LEFT", "BODY-two"), shortText("MID", "BODY-two"),
                        shortText("RIGHT", "BODY-two"));
                writeRow(cs, 660, shortText("LEFT", "BODY-three"), shortText("MID", "BODY-three"),
                        shortText("RIGHT", "BODY-three"));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** 溢出到邻栏的长行文本（宽度超过栏距，与邻栏字符区间重叠） */
    private String longText(String column) {
        return column + "-LONG heading " + "x".repeat(20);
    }

    /** 铺满栏宽但不出栏的文本（栏沟只在真实栏缝处留白） */
    private String shortText(String column, String tag) {
        return column + "-" + tag + " text " + "y".repeat(10);
    }

    /** 三栏同排写一行 */
    private void writeRow(PDPageContentStream cs, float y, String left, String mid, String right)
            throws Exception {
        writeLine(cs, COLUMNS[0], y, left);
        writeLine(cs, COLUMNS[1], y, mid);
        writeLine(cs, COLUMNS[2], y, right);
    }

    private void writeLine(PDPageContentStream cs, float x, float y, String text) throws Exception {
        cs.beginText();
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }
}
