package com.workbuddy.worker.parser.pdf;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.extract.LineTexts;
import com.workbuddy.worker.parser.pdf.layout.ProjectionPageLayoutAnalyzer;
import com.workbuddy.worker.parser.pdf.model.CharInfo;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.workbuddy.worker.testkit.PropertiesFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行文本空格口径单测：字距加宽排版（字符间距成片）不得把单词拆成单字符，
 * 真实空格字符与词边界补空格保持原口径。样例文件见 samples/pdf-parse-01。
 */
class PdfCharacterSpacingTest {

    private static final float EM = 12f;

    private static final String CJK_FONT = "C:/Windows/Fonts/simhei.ttf";

    private final PdfBoxDocumentParser parser =
            new PdfBoxDocumentParser(new ProjectionPageLayoutAnalyzer());

    private LineTexts.Options options() {
        return LineTexts.Options.of(PropertiesFixtures.parse());
    }

    /**
     * 守护 {@code characterSpacingShouldNotInsertSpaces}：该场景行为改动即失败。
     */
    @Test
    void characterSpacingShouldNotInsertSpaces() {
        // 字距加宽时 PDFBox 把每个字符当作一次独立的词回调，间隙均匀
        assertEquals("Hello", LineTexts.lineText(spacedWord("Hello", 100, 7, 3), options()));
    }

    /**
     * 守护 {@code wordBoundaryGapShouldKeepSpace}：该场景行为改动即失败。
     */
    @Test
    void wordBoundaryGapShouldKeepSpace() {
        List<CharInfo> chars = new ArrayList<>(word("Hello", 100));
        chars.addAll(word("World", 140));

        assertEquals("Hello World", LineTexts.lineText(chars, options()));
    }

    /**
     * 守护 {@code realSpaceShouldSurviveCharacterSpacing}：该场景行为改动即失败。
     */
    @Test
    void realSpaceShouldSurviveCharacterSpacing() {
        List<CharInfo> chars = new ArrayList<>(spacedWord("Hello", 100, 7, 3));
        double spaceX = 100 + 5 * (7 + 3);
        chars.add(new CharInfo(spaceX, 100, 3, 10, EM, "Helvetica", false, ' '));
        chars.addAll(spacedWord("World", spaceX + 6, 7, 3));

        assertEquals("Hello World", LineTexts.lineText(chars, options()));
    }

    /**
     * 守护 {@code cjkBoundaryShouldStillSuppressSpace}：该场景行为改动即失败。
     */
    @Test
    void cjkBoundaryShouldStillSuppressSpace() {
        List<CharInfo> chars = new ArrayList<>(spacedWord("中文", 100, 12, 4));

        assertEquals("中文", LineTexts.lineText(chars, options()));
    }

    /**
     * 守护 {@code plainEnglishShouldKeepWordSpaces}：该场景行为改动即失败。
     */
    @Test
    void plainEnglishShouldKeepWordSpaces() throws Exception {
        List<String> texts = paragraphTexts(pdf(false));

        assertEquals(List.of("Quarterly Report 2024", "Revenue grew steadily across all regions."), texts);
    }

    /**
     * 守护 {@code spacedTitleShouldNotSplitWords}：该场景行为改动即失败。
     */
    @Test
    void spacedTitleShouldNotSplitWords() throws Exception {
        List<String> texts = paragraphTexts(pdf(true));

        assertEquals(List.of("INVOICE SUMMARY", "Prepared by the finance department."), texts);
    }

    /**
     * 守护 {@code spacedNumbersShouldStayWhole}：该场景行为改动即失败。
     */
    @Test
    void spacedNumbersShouldStayWhole() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = newPage(doc);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                line(cs, font, 12, 3, 60, 740, "Contract No. ABC-1234");
                line(cs, font, 12, 3, 60, 700, "Amount CNY 1,234,567.00 due on 2024-06-30.");
            }
            List<String> texts = paragraphTexts(save(doc));

            assertEquals(List.of("Contract No. ABC-1234",
                    "Amount CNY 1,234,567.00 due on 2024-06-30."), texts);
        }
    }

    /**
     * 守护 {@code chineseBodyAndSpacedNumberShouldStayWhole}：该场景行为改动即失败。
     */
    @Test
    void chineseBodyAndSpacedNumberShouldStayWhole() throws Exception {
        Assumptions.assumeTrue(new File(CJK_FONT).exists(), "缺少中文字体，跳过");
        List<String> texts = paragraphTexts(chineseDoc());

        assertTrue(texts.stream().anyMatch(t -> t.contains("本项目建设内容包括知识库平台与检索服务，工期为九十日历天。")),
                () -> "texts=" + texts);
        assertTrue(texts.contains("合同编号：HT-2024-0087"), () -> "texts=" + texts);
    }

    /**
     * 守护 {@code spacedCellNumberShouldStayWhole}：该场景行为改动即失败。
     */
    @Test
    void spacedCellNumberShouldStayWhole() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = newPage(doc);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float x0 = 60;
                float y0 = 700;
                float[] cols = {0, 120, 300};
                float[] rows = {0, 24, 48};
                cs.setLineWidth(0.8f);
                for (float dy : rows) {
                    cs.moveTo(x0 + cols[0], y0 - dy);
                    cs.lineTo(x0 + cols[2], y0 - dy);
                }
                for (float dx : cols) {
                    cs.moveTo(x0 + dx, y0 - rows[0]);
                    cs.lineTo(x0 + dx, y0 - rows[2]);
                }
                cs.stroke();

                PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                line(cs, font, 11, 0, x0 + 6, y0 - 16, "Contract No.");
                line(cs, font, 11, 3, x0 + cols[1] + 6, y0 - 16, "ABC-1234");
                line(cs, font, 11, 0, x0 + 6, y0 - 40, "Amount");
                line(cs, font, 11, 3, x0 + cols[1] + 6, y0 - 40, "1,234,567");
            }
            ParseSource source = parse(save(doc));
            ParseElement table = source.getElements().stream()
                    .filter(e -> "TABLE".equals(e.getType()))
                    .findFirst().orElse(null);

            assertTrue(table != null, () -> "elements=" + source.getElements());
            assertEquals(List.of("Contract No.", "ABC-1234", "Amount", "1,234,567"),
                    table.getCells().stream().map(ParseElement::getText).toList());
        }
    }

    // ---------------- 样例构造 ----------------

    /** 英文样例：标题 18pt，正文 12pt；spacedTitle 为真时标题加 4pt 字距（正文不加） */
    private byte[] pdf(boolean spacedTitle) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = newPage(doc);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                if (spacedTitle) {
                    line(cs, font, 18, 4, 60, 740, "INVOICE SUMMARY");
                    line(cs, font, 12, 0, 60, 700, "Prepared by the finance department.");
                } else {
                    line(cs, font, 18, 0, 60, 740, "Quarterly Report 2024");
                    line(cs, font, 12, 0, 60, 700, "Revenue grew steadily across all regions.");
                }
            }
            return save(doc);
        }
    }

    /** 中文样例：22pt 字距加宽标题、12pt 正文、12pt 字距加宽编号行 */
    private byte[] chineseDoc() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = newPage(doc);
            PDFont cjk = PDType0Font.load(doc, new File(CJK_FONT));
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                line(cs, cjk, 22, 6, 60, 760, "投标文件");
                line(cs, cjk, 12, 0, 60, 720, "本项目建设内容包括知识库平台与检索服务，工期为九十日历天。");
                line(cs, cjk, 12, 3, 60, 690, "合同编号：HT-2024-0087");
            }
            return save(doc);
        }
    }

    private PDPage newPage(PDDocument doc) {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        return page;
    }

    /** 一行文字：显式设置字距（Tc 属于文本状态，会跨 BT/ET 保留，0 也要显式设） */
    private void line(PDPageContentStream cs, PDFont font, float size, float spacing,
                      float x, float y, String text) throws Exception {
        cs.beginText();
        cs.setFont(font, size);
        cs.setCharacterSpacing(spacing);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private byte[] save(PDDocument doc) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }

    private List<String> paragraphTexts(byte[] data) {
        return parse(data).getElements().stream()
                .filter(e -> "PARAGRAPH".equals(e.getType()))
                .map(ParseElement::getText)
                .toList();
    }

    private ParseSource parse(byte[] data) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-space");
        fileRef.setFileName("space.pdf");
        fileRef.setMimeType("application/pdf");
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(PropertiesFixtures.parse());
        return parser.parse(context);
    }

    /** 字距加宽排版的字符事实：字符间隙固定且成片 */
    private List<CharInfo> spacedWord(String text, double x, double width, double gap) {
        List<CharInfo> result = new ArrayList<>();
        double cursor = x;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            result.add(new CharInfo(cursor, 100, width, 10, EM, "Helvetica", false, cp));
            cursor += width + gap;
            i += Character.charCount(cp);
        }
        return result;
    }

    /** 紧排的一个词：字符间隙 0（词间空格由测试数据另行留出间隙或插入空白字符） */
    private List<CharInfo> word(String text, double x) {
        List<CharInfo> result = new ArrayList<>();
        double cursor = x;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            result.add(new CharInfo(cursor, 100, 7, 10, EM, "Helvetica", false, cp));
            cursor += 7;
            i += Character.charCount(cp);
        }
        return result;
    }
}
