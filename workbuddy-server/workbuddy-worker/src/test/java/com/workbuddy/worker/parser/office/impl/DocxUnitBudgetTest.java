package com.workbuddy.worker.parser.office.impl;

import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.worker.parser.ParseContext;
import org.apache.poi.util.Units;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DOCX 判定单元与页级指标单测：字符预算切单元、图片归属、表格/页眉字符计入与指标回填。
 */
class DocxUnitBudgetTest {

    private final DocxTestFixture fixture = new DocxTestFixture();

    /** 图片按位置归属单元：文本先推到第二单元，图片计入它所在的单元（不再一律记首单元）（{@code pictureShouldBelongToItsUnitByPosition}）。 */
    @Test
    void pictureShouldBelongToItsUnitByPosition() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("abcdefgh");
            XWPFRun run = doc.createParagraph().createRun();
            run.addPicture(new ByteArrayInputStream(pngBytes()), XWPFDocument.PICTURE_TYPE_PNG, "p.png",
                    Units.toEMU(8), Units.toEMU(8));

            ParseContext context = fixture.context(DocxTestFixture.save(doc));
            context.getProperties().setOfficePageChars(6);
            ParseSource source = fixture.parse(context);

            List<PageMetric> metrics = source.getPageMetrics();
            assertEquals(2, metrics.size(), () -> "metrics=" + metrics);
            assertEquals(0, metrics.getFirst().getImageCount());
            assertEquals(1, metrics.get(1).getImageCount());
        }
    }

    /** 表格单元格文本计入单元预算：只有表格、单元格共 12 个非空白字符、预算 6 → 两个单元（{@code tableCellTextShouldCountIntoUnitBudget}）。 */
    @Test
    void tableCellTextShouldCountIntoUnitBudget() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFTable table = doc.createTable(1, 2);
            table.getRow(0).getCell(0).setText("abcdef");
            table.getRow(0).getCell(1).setText("ghijkl");

            ParseContext context = fixture.context(DocxTestFixture.save(doc));
            context.getProperties().setOfficePageChars(6);
            ParseSource source = fixture.parse(context);

            assertEquals(2, source.getUnitCount(), () -> "metrics=" + source.getPageMetrics());
            assertTrue(source.getPageMetrics().getFirst().getCharCount() > 0,
                    () -> "metrics=" + source.getPageMetrics());
        }
    }

    /** 页眉文本计入单元预算：正文为空但页眉有字 → 单元字符数不为 0（{@code headerTextShouldCountIntoUnitBudget}）。 */
    @Test
    void headerTextShouldCountIntoUnitBudget() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
            header.createParagraph().createRun().setText("页眉文字");

            ParseContext context = fixture.context(DocxTestFixture.save(doc));
            context.getProperties().setOfficePageChars(6);
            ParseSource source = fixture.parse(context);

            assertTrue(source.getPageMetrics().getFirst().getCharCount() > 0,
                    () -> "metrics=" + source.getPageMetrics());
        }
    }

    /**
     * 守护 {@code docxShouldFillUnitMetrics}：该场景行为改动即失败。
     */
    @Test
    void docxShouldFillUnitMetrics() throws Exception {
        ParseSource source = fixture.parse(fixture.buildDocx());

        assertEquals(1, source.getUnitCount());
        PageMetric metric = source.getPageMetrics().getFirst();
        assertTrue(metric.getCharCount() > 0);
        assertEquals(0, metric.getImageCount());
        assertEquals(0.0, metric.getGarbledRatio());
    }

    /**
     * 守护 {@code longDocxShouldSplitUnitsByCharBudget}：该场景行为改动即失败。
     */
    @Test
    void longDocxShouldSplitUnitsByCharBudget() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("估".repeat(6001));

            ParseSource source = fixture.parse(DocxTestFixture.save(doc));

            // Azure 页单位口径：3000 字符 = 1 个判定单元 → 6001 字符 = 3 个单元
            assertEquals(3, source.getUnitCount());
            assertEquals(3, source.getPageMetrics().size());
        }
    }

    /**
     * 守护 {@code imageOnlyDocxShouldReportImageUnit}：该场景行为改动即失败。
     */
    @Test
    void imageOnlyDocxShouldReportImageUnit() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFRun run = doc.createParagraph().createRun();
            run.addPicture(new ByteArrayInputStream(pngBytes()), XWPFDocument.PICTURE_TYPE_PNG, "p.png",
                    Units.toEMU(80), Units.toEMU(80));

            ParseSource source = fixture.parse(DocxTestFixture.save(doc));

            PageMetric metric = source.getPageMetrics().getFirst();
            assertEquals(0, metric.getCharCount());
            assertEquals(1, metric.getImageCount());
        }
    }

    /** 8×8 PNG（图片单元用例的输入） */
    private byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
