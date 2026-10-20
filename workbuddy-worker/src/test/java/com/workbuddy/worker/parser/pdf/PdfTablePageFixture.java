package com.workbuddy.worker.parser.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;

/**
 * 表格类 PDF 测试文档夹具：带框线表与稀疏线框网格。
 */
final class PdfTablePageFixture {

    /**
     * 造一张带框线的表：三行三列，首行横向合并、第二行只填一格。
     *
     * @return PDF 字节
     * @throws IOException 写入失败
     */
    byte[] buildRuledTable() throws IOException {
        return buildRuledTable(true);
    }

    /**
     * 造一张带框线的表；withText 为 false 时只画线不写文字（空白率门禁用例）。
     *
     * @param withText 是否写单元格文字
     * @return PDF 字节
     * @throws IOException 写入失败
     */
    byte[] buildRuledTable(boolean withText) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            float left = 72;
            float right = 372;
            float top = 842 - 200;
            float middle = 842 - 230;
            float lower = 842 - 260;
            float bottom = 842 - 290;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.8f);
                PdfParserTestFixture.drawLine(cs, left, top, right, top);
                PdfParserTestFixture.drawLine(cs, left, middle, right, middle);
                PdfParserTestFixture.drawLine(cs, left, lower, right, lower);
                PdfParserTestFixture.drawLine(cs, left, bottom, right, bottom);
                PdfParserTestFixture.drawLine(cs, left, bottom, left, top);
                PdfParserTestFixture.drawLine(cs, right, bottom, right, top);
                PdfParserTestFixture.drawLine(cs, 172, bottom, 172, middle);
                PdfParserTestFixture.drawLine(cs, 272, bottom, 272, middle);
                if (withText) {
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    cs.beginText();
                    cs.newLineAtOffset(left + 10, middle + 8);
                    cs.showText("Score sheet");
                    cs.endText();
                    cs.beginText();
                    cs.newLineAtOffset(left + 10, lower + 8);
                    cs.showText("A1");
                    cs.endText();
                    cs.beginText();
                    cs.newLineAtOffset(182, lower + 8);
                    cs.showText("Bidding price");
                    cs.endText();
                    cs.beginText();
                    cs.newLineAtOffset(282, bottom + 8);
                    cs.showText("30");
                    cs.endText();
                }
            }
            return PdfParserTestFixture.save(doc);
        }
    }

    /**
     * 造一张 2 行 6 列的线框网格，只填一格（填充率低于假表门限）。
     *
     * @return PDF 字节
     * @throws IOException 写入失败
     */
    byte[] buildSparseRuledGrid() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(595, 842));
            doc.addPage(page);
            float left = 72;
            float right = 372;
            float top = 842 - 200;
            float middle = 842 - 245;
            float bottom = 842 - 290;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.8f);
                PdfParserTestFixture.drawLine(cs, left, top, right, top);
                PdfParserTestFixture.drawLine(cs, left, middle, right, middle);
                PdfParserTestFixture.drawLine(cs, left, bottom, right, bottom);
                for (int i = 0; i <= 6; i++) {
                    float x = left + i * 50;
                    PdfParserTestFixture.drawLine(cs, x, bottom, x, top);
                }
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                cs.beginText();
                cs.newLineAtOffset(left + 10, top - 8);
                cs.showText("X");
                cs.endText();
            }
            return PdfParserTestFixture.save(doc);
        }
    }
}
