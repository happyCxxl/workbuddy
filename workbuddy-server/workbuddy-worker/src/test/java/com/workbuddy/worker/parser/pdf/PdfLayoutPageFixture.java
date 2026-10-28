package com.workbuddy.worker.parser.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.util.List;

/**
 * 版面类 PDF 测试文档夹具：基础页、页眉页脚/页底数字页、双栏页与跨栏标题页。
 */
final class PdfLayoutPageFixture {

    /**
     * 造两页 PDF：页眉 + 两行正文。
     *
     * @return PDF 字节
     * @throws IOException 写入失败
     */
    byte[] buildPdf() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int page = 0; page < 2; page++) {
                PDPage pdfPage = new PDPage(new PDRectangle(595, 842));
                doc.addPage(pdfPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdfPage)) {
                    // 页眉：两页同位置同文本（顶部）
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    cs.newLineAtOffset(250, 800);
                    cs.showText("XX Project Header");
                    cs.endText();
                    // 正文两行
                    cs.beginText();
                    cs.newLineAtOffset(72, 700);
                    cs.showText("Chapter one body line " + page);
                    cs.endText();
                    cs.beginText();
                    cs.newLineAtOffset(72, 680);
                    cs.showText("Chapter one body continues " + page);
                    cs.endText();
                }
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    /**
     * 造 PDF：每页写入可选的页眉与正文，以及该页给定的页底纯数字行（同页多条按 y 错开成不同行）。
     *
     * @param bottomNumbers 每页的页底数字（空列表表示该页无页底数字）
     * @param headerText    页眉文本（null 表示不写）
     * @param bodyText      正文文本（null 表示不写）
     * @return PDF 字节
     * @throws IOException 写入失败
     */
    byte[] buildPages(List<List<BottomNumber>> bottomNumbers, String headerText,
                      String bodyText) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int pageIndex = 0; pageIndex < bottomNumbers.size(); pageIndex++) {
                PDPage page = new PDPage(new PDRectangle(595, 842));
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    if (headerText != null) {
                        cs.beginText();
                        cs.newLineAtOffset(72, 800);
                        cs.showText(headerText);
                        cs.endText();
                    }
                    if (bodyText != null) {
                        cs.beginText();
                        cs.newLineAtOffset(72, 400);
                        cs.showText(bodyText);
                        cs.endText();
                    }
                    List<BottomNumber> numbers = bottomNumbers.get(pageIndex);
                    for (int i = 0; i < numbers.size(); i++) {
                        cs.beginText();
                        cs.newLineAtOffset(numbers.get(i).x(), 30 + i * 12);
                        cs.showText(numbers.get(i).text());
                        cs.endText();
                    }
                }
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    /**
     * 造双栏 PDF：左右各三行正文（栏沟约 100pt）。
     *
     * @return PDF 字节
     * @throws IOException 写入失败
     */
    byte[] buildTwoColumnPage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeColumn(cs, 72, 700, leftColumn());
                writeColumn(cs, 330, 700, rightColumn());
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    /**
     * 造双栏 PDF：页顶跨栏标题 + 左右各三行 + 中段跨栏标题 + 左右各两行。
     *
     * @return PDF 字节
     * @throws IOException 写入失败
     */
    byte[] buildSpanningHeadingPage() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                writeHeading(cs, 760, "Spanning Heading Number One Across Both Columns");
                writeColumn(cs, 72, 700, leftColumn());
                writeColumn(cs, 330, 700, rightColumn());
                writeHeading(cs, 640, "Spanning Heading Number Two Across Both Columns");
                writeColumn(cs, 72, 600, leftTail());
                writeColumn(cs, 330, 600, rightTail());
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    private String[] leftColumn() {
        return new String[] {"Left column first line here", "Left column second line here",
                "Left column third line here"};
    }

    private String[] rightColumn() {
        return new String[] {"Right column first line here", "Right column second line here",
                "Right column third line here"};
    }

    private String[] leftTail() {
        return new String[] {"Left column fourth line here", "Left column fifth line here"};
    }

    private String[] rightTail() {
        return new String[] {"Right column fourth line here", "Right column fifth line here"};
    }

    /** 从 startY 起按 12pt 行距写一栏（10pt 字号） */
    private void writeColumn(PDPageContentStream cs, float x, float startY, String[] lines) throws IOException {
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
        float y = startY;
        for (String line : lines) {
            cs.beginText();
            cs.newLineAtOffset(x, y);
            cs.showText(line);
            cs.endText();
            y -= 12;
        }
    }

    /** 写一条横跨两栏的标题（14pt 字号，从左边距起足够长） */
    private void writeHeading(PDPageContentStream cs, float y, String text) throws IOException {
        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
        cs.beginText();
        cs.newLineAtOffset(72, y);
        cs.showText(text);
        cs.endText();
    }

    /**
     * 页底数字（文本 + x 位置）。
     *
     * @param text 文本
     * @param x    横坐标
     */
    record BottomNumber(String text, float x) {
    }

    /**
     * 造一个页底数字。
     *
     * @param text 文本
     * @param x    横坐标
     * @return 页底数字
     */
    static BottomNumber bottomNumber(String text, float x) {
        return new BottomNumber(text, x);
    }
}
