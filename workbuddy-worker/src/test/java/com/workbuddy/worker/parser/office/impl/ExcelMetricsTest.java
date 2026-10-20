package com.workbuddy.worker.parser.office.impl;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ElementType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;

import static com.workbuddy.worker.parser.office.impl.ExcelTestFixture.MIME_XLSX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 页级指标单测：工作表内嵌图片元素/事实与单元字符、图片计数。
 */
class ExcelMetricsTest {

    private final ExcelTestFixture fixture = new ExcelTestFixture();

    /** 工作表内嵌图片：产 IMAGE 元素（needsOcr）并按表记一条 OCR_IMAGE 事实（{@code embeddedPictureShouldEmitImageAndFact}）。 */
    @Test
    void embeddedPictureShouldEmitImageAndFact() throws Exception {
        ParseSource source = fixture.parse(buildXlsxWithPicture(), MIME_XLSX);

        List<ParseElement> images = source.getElements().stream()
                .filter(e -> ElementType.IMAGE.name().equals(e.getType())).toList();
        assertEquals(1, images.size(), () -> "elements=" + source.getElements());
        assertEquals(Boolean.TRUE, images.getFirst().getNeedsOcr());
        assertTrue(images.getFirst().getAssetRef().startsWith("image-0"),
                () -> "asset=" + images.getFirst().getAssetRef());
        assertEquals("office#sheet[图表]/picture[0]", images.getFirst().getProvenance().getPath());
        assertTrue(source.getFacts().stream()
                        .anyMatch(fact -> fact.getEvidence().contains("本表 1 张")),
                () -> "facts=" + source.getFacts());
    }

    /** 一表两列数据 + 一张内嵌 PNG（1×1 透明图） */
    private byte[] buildXlsxWithPicture() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("图表");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("A");
            row.createCell(1).setCellValue("B");
            byte[] png = Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==");
            int pictureIndex = workbook.addPicture(png, Workbook.PICTURE_TYPE_PNG);
            XSSFDrawing drawing = (XSSFDrawing) sheet.createDrawingPatriarch();
            drawing.createPicture(new XSSFClientAnchor(0, 0, 0, 0, 2, 2, 4, 6), pictureIndex);
            return ExcelTestFixture.save(workbook);
        }
    }

    /**
     * 守护 {@code excelShouldFillSheetMetricsIncludingEmptySheet}：该场景行为改动即失败。
     */
    @Test
    void excelShouldFillSheetMetricsIncludingEmptySheet() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet first = workbook.createSheet("有数据");
            Row header = first.createRow(0);
            header.createCell(0).setCellValue("项目");
            header.createCell(1).setCellValue("金额");
            workbook.createSheet("空表");

            ParseSource source = fixture.parse(ExcelTestFixture.save(workbook), MIME_XLSX);

            // 每 worksheet = 1 个判定单元；空 sheet 单元字符数为 0
            assertEquals(2, source.getUnitCount());
            assertTrue(source.getPageMetrics().get(0).getCharCount() > 0);
            assertEquals(0, source.getPageMetrics().get(1).getCharCount());
            assertEquals(0, source.getPageMetrics().get(1).getImageCount());
        }
    }

    /**
     * 守护 {@code sheetPictureShouldBeCountedInMetrics}：该场景行为改动即失败。
     */
    @Test
    void sheetPictureShouldBeCountedInMetrics() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("带图");
            sheet.createRow(0).createCell(0).setCellValue("见图");
            int pictureIndex = workbook.addPicture(pngBytes(), Workbook.PICTURE_TYPE_PNG);
            XSSFDrawing drawing = (XSSFDrawing) sheet.createDrawingPatriarch();
            drawing.createPicture(new XSSFClientAnchor(0, 0, 0, 0, 1, 1, 3, 5), pictureIndex);

            ParseSource source = fixture.parse(ExcelTestFixture.save(workbook), MIME_XLSX);

            assertEquals(1, source.getPageMetrics().getFirst().getImageCount());
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
