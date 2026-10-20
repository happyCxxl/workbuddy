package com.workbuddy.worker.parser.office.impl;
import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 原生解析器单测：XLSX 合并区域、XLS 同路径、supports 与能力标识。
 */
class ExcelDocumentParserTest {

    private static final String MIME_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String MIME_XLS = "application/vnd.ms-excel";

    private final ExcelDocumentParser parser = new ExcelDocumentParser();

    private ParseContext context(byte[] data, String mimeType) {
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("F-1");
        fileRef.setFileName("t");
        fileRef.setMimeType(mimeType);
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(new ParseProperties());
        return context;
    }

    /** 首行为空（行对象存在但无单元格）：cols 按实际数据算，不出负数也不抛异常（{@code emptyFirstRowShouldStillGiveDataColumns}）。 */
    @Test
    void emptyFirstRowShouldStillGiveDataColumns() throws Exception {
        ParseSource source = parser.parse(context(buildXlsxWithEmptyFirstRow(), MIME_XLSX));

        ParseElement table = source.getElements().stream()
                .filter(element -> ElementType.TABLE.name().equals(element.getType())).findFirst().orElseThrow();
        assertEquals(2, table.getCols(), () -> "cols=" + table.getCols());
        // 行数按真实行号区间算：空的首行也占一号，单元格 row 索引保持 1、2
        assertEquals(3, table.getRows());
        assertTrue(cellTexts(source).contains("A1 报价"), () -> "cells=" + cellTexts(source));
        assertTrue(cellTexts(source).contains("分值"), () -> "cells=" + cellTexts(source));
    }

    /** 承接列数口径：数据区比首行宽时，cols 取数据最大列，不被首行截短（{@code narrowerFirstRowShouldNotTruncateColumns}）。 */
    @Test
    void narrowerFirstRowShouldNotTruncateColumns() throws Exception {
        ParseSource source = parser.parse(context(buildXlsxWithNarrowTitleRow(), MIME_XLSX));

        ParseElement table = source.getElements().stream()
                .filter(element -> ElementType.TABLE.name().equals(element.getType())).findFirst().orElseThrow();
        assertEquals(3, table.getCols(), () -> "cols=" + table.getCols());
    }

    /** 合并区被覆盖格带文本：并入锚格（换行分隔），不丢（{@code coveredCellTextShouldBeMergedIntoAnchor}）。 */
    @Test
    void coveredCellTextShouldBeMergedIntoAnchor() throws Exception {
        ParseSource source = parser.parse(context(buildXlsxWithCoveredText(), MIME_XLSX));

        List<String> texts = cellTexts(source);
        assertTrue(texts.stream().anyMatch(text -> text.contains("合并块") && text.contains("被覆盖值")),
                () -> "cells=" + texts);
    }

    /** 页眉页脚产元素：三区拼接成一条 HEADER、页脚一条 FOOTER，格式码被清掉（{@code sheetHeaderFooterShouldEmitElements}）。 */
    @Test
    void sheetHeaderFooterShouldEmitElements() throws Exception {
        ParseSource source = parser.parse(context(buildXlsxWithHeaderFooter(), MIME_XLSX));

        List<ParseElement> headers = source.getElements().stream()
                .filter(e -> ElementType.HEADER.name().equals(e.getType())).toList();
        List<ParseElement> footers = source.getElements().stream()
                .filter(e -> ElementType.FOOTER.name().equals(e.getType())).toList();
        assertEquals(1, headers.size(), () -> "elements=" + source.getElements());
        assertEquals(1, footers.size());
        assertTrue(headers.getFirst().getText().contains("报表标题"), () -> "header=" + headers.getFirst().getText());
        assertTrue(headers.getFirst().getText().contains("机密"), () -> "header=" + headers.getFirst().getText());
        assertFalse(headers.getFirst().getText().contains("&"), () -> "header=" + headers.getFirst().getText());
        assertTrue(footers.getFirst().getText().contains("第 1 页"), () -> "footer=" + footers.getFirst().getText());
        assertEquals("office#sheet[页眉表]/header", headers.getFirst().getProvenance().getPath());
    }

    /** 格式码清理：段标记转分隔、格式码去掉、空白合并（{@code headerFooterCodesShouldBeStripped}）。 */
    @Test
    void headerFooterCodesShouldBeStripped() {
        assertEquals("左 中 右", ExcelDocumentParser.sheetHeaderFooterText("&L左&C中&R右"));
        assertEquals("报表 & 页", ExcelDocumentParser.sheetHeaderFooterText("&\"Arial,Bold\"报表 && 页"));
        assertEquals("", ExcelDocumentParser.sheetHeaderFooterText("   "));
    }

    /** 工作表内嵌图片：产 IMAGE 元素（needsOcr）并按表记一条 OCR_IMAGE 事实（{@code embeddedPictureShouldEmitImageAndFact}）。 */
    @Test
    void embeddedPictureShouldEmitImageAndFact() throws Exception {
        ParseSource source = parser.parse(context(buildXlsxWithPicture(), MIME_XLSX));

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
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** 首行空时表头行指向真实数据首行（第 1 行），isHeader 只标该行（{@code headerRowShouldPointToFirstDataRow}）。 */
    @Test
    void headerRowShouldPointToFirstDataRow() throws Exception {
        ParseSource source = parser.parse(context(buildXlsxWithEmptyFirstRow(), MIME_XLSX));

        ParseElement table = source.getElements().stream()
                .filter(element -> ElementType.TABLE.name().equals(element.getType())).findFirst().orElseThrow();
        assertEquals(1, table.getHeaderRow(), () -> "headerRow=" + table.getHeaderRow());
        assertTrue(table.getCells().stream()
                        .filter(cell -> Integer.valueOf(1).equals(cell.getRow()))
                        .allMatch(cell -> Boolean.TRUE.equals(cell.getIsHeader())),
                () -> "cells=" + table.getCells());
        assertTrue(table.getCells().stream()
                        .filter(cell -> Integer.valueOf(2).equals(cell.getRow()))
                        .noneMatch(cell -> Boolean.TRUE.equals(cell.getIsHeader())),
                () -> "cells=" + table.getCells());
    }

    /** .xls 公式缓存不可辨别：有公式格时按档在路说明里留痕，取值仍按缓存值（{@code xlsFormulaCacheShouldBeNoted}）。 */
    @Test
    void xlsFormulaCacheShouldBeNoted() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("S1");
            sheet.createRow(0).createCell(0).setCellValue("指标");
            sheet.createRow(1).createCell(0).setCellFormula("1+1");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);

            ParseSource source = parser.parse(context(out.toByteArray(), MIME_XLS));

            assertTrue(source.getNote().contains("旧格式公式缓存可能未计算"),
                    () -> "note=" + source.getNote());
            assertTrue(source.getNote().contains("1 个公式格"), () -> "note=" + source.getNote());
        }
    }

    /** xlsx 公式缓存可辨别：不留旧格式口径说明（{@code xlsxFormulaCacheShouldNotBeNoted}）。 */
    @Test
    void xlsxFormulaCacheShouldNotBeNoted() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("S1");
            sheet.createRow(0).createCell(0).setCellValue("指标");
            sheet.createRow(1).createCell(0).setCellFormula("1+1");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);

            ParseSource source = parser.parse(context(out.toByteArray(), MIME_XLSX));

            assertEquals(null, source.getNote());
        }
    }

    /** 公式格以缓存显示值为准：手工把 <v> 写成 7（公式现算是 2）→ 产物取 7（{@code formulaShouldPreferCachedDisplayValue}）。 */
    @Test
    void formulaShouldPreferCachedDisplayValue() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("公式表");
            sheet.createRow(0).createCell(0).setCellValue("指标");
            XSSFCell formula = (XSSFCell) sheet.createRow(1).createCell(0);
            formula.setCellFormula("1+1");
            formula.getCTCell().setV("7");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);

            ParseSource source = parser.parse(context(out.toByteArray(), MIME_XLSX));

            assertTrue(cellTexts(source).contains("7"), () -> "cells=" + cellTexts(source));
            assertFalse(cellTexts(source).contains("2"), () -> "cells=" + cellTexts(source));
        }
    }

    /** 一表两列数据 + 页眉三区 + 页脚 */
    private byte[] buildXlsxWithHeaderFooter() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("页眉表");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("A");
            row.createCell(1).setCellValue("B");
            sheet.getHeader().setLeft("报表标题");
            sheet.getHeader().setRight("机密");
            sheet.getFooter().setCenter("第 1 页");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** 首行两列，第 2 行横向合并 A2:B2，且被覆盖的 B2 自带文本 */
    private byte[] buildXlsxWithCoveredText() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("覆盖文本");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("列A");
            header.createCell(1).setCellValue("列B");
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 1));
            Row merged = sheet.createRow(1);
            merged.createCell(0).setCellValue("合并块");
            merged.createCell(1).setCellValue("被覆盖值");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** 首行只有标题一格，数据行有三列 */
    private byte[] buildXlsxWithNarrowTitleRow() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("窄标题");
            sheet.createRow(0).createCell(0).setCellValue("导出报表");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("A");
            data.createCell(1).setCellValue("B");
            data.createCell(2).setCellValue("C");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** 首行是存在的空行（无任何单元格），数据从第 2 行开始 */
    private byte[] buildXlsxWithEmptyFirstRow() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("空首行");
            sheet.createRow(0);
            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("评分项");
            header.createCell(1).setCellValue("分值");
            Row data = sheet.createRow(2);
            data.createCell(0).setCellValue("A1 报价");
            data.createCell(1).setCellValue(30);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private byte[] buildXlsx() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("评分表");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("评分项");
            header.createCell(1).setCellValue("分值");
            Row first = sheet.createRow(1);
            first.createCell(0).setCellValue("A1 报价");
            first.createCell(1).setCellValue(30);
            // A3:A4 纵向合并
            sheet.addMergedRegion(new CellRangeAddress(2, 3, 0, 0));
            Row merged = sheet.createRow(2);
            merged.createCell(0).setCellValue("合并单元格");
            merged.createCell(1).setCellValue(15);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** 全部表格单元格文本 */
    private List<String> cellTexts(ParseSource source) {
        return source.getElements().stream()
                .filter(e -> ElementType.TABLE.name().equals(e.getType()))
                .flatMap(e -> e.getCells().stream())
                .map(ParseElement::getText).toList();
    }

    /**
     * 守护 {@code xlsxShouldParseSheetTableWithMerges}：该场景行为改动即失败。
     */
    @Test
    void xlsxShouldParseSheetTableWithMerges() throws Exception {
        ParseSource source = parser.parse(context(buildXlsx(), MIME_XLSX));

        assertEquals(1, source.getUnitCount());
        ParseElement table = source.getElements().stream()
                .filter(e -> ElementType.TABLE.name().equals(e.getType()))
                .findFirst().orElse(null);
        assertNotNull(table);
        assertEquals(0, table.getPage());
        ParseElement merged = table.getCells().stream()
                .filter(c -> "合并单元格".equals(c.getText()))
                .findFirst().orElse(null);
        assertNotNull(merged);
        assertEquals(2, merged.getRowSpan());
        assertNull(merged.getColSpan());
        // 表头候选：第一行单元格 isHeader
        ParseElement headerCell = table.getCells().stream()
                .filter(c -> "评分项".equals(c.getText()))
                .findFirst().orElse(null);
        assertNotNull(headerCell);
        assertTrue(headerCell.getIsHeader());
        assertNull(table.getProvenance());
        assertTrue(merged.getProvenance().getPath().contains("sheet[评分表]"));
    }

    /**
     * 守护 {@code xlsShouldParseViaSameWorkbookPath}：该场景行为改动即失败。
     */
    @Test
    void xlsShouldParseViaSameWorkbookPath() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("S1");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("列A");
            row.createCell(1).setCellValue("列B");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            ParseSource source = parser.parse(context(out.toByteArray(), MIME_XLS));
            assertEquals(1, source.getUnitCount());
            assertEquals(1, source.getElements().size());
            assertTrue(source.getElements().getFirst().getCells().size() >= 2);
        }
    }

    /**
     * 守护 {@code formulaCellShouldUseCachedValueInsteadOfFormulaText}：该场景行为改动即失败。
     */
    @Test
    void formulaCellShouldUseCachedValueInsteadOfFormulaText() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("S1");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("项目");
            header.createCell(1).setCellValue("金额");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("合计");
            Cell formula = row.createCell(1);
            formula.setCellFormula("SUM(2,3)");
            // 算一次写入缓存值，模拟 Excel 保存时的显示值
            workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);

            ParseSource source = parser.parse(context(out.toByteArray(), MIME_XLSX));

            assertTrue(cellTexts(source).contains("5"), () -> "cells=" + cellTexts(source));
            assertTrue(cellTexts(source).stream().noneMatch(text -> text.contains("SUM")),
                    () -> "cells=" + cellTexts(source));
        }
    }

    /**
     * 守护 {@code formulaWithoutCachedValueShouldBeEvaluated}：该场景行为改动即失败。
     */
    @Test
    void formulaWithoutCachedValueShouldBeEvaluated() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("S1");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("合计");
            row.createCell(1).setCellFormula("1+2");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);

            ParseSource source = parser.parse(context(out.toByteArray(), MIME_XLSX));

            assertTrue(cellTexts(source).contains("3"), () -> "cells=" + cellTexts(source));
        }
    }

    /**
     * 守护 {@code unsupportedFormulaShouldFallBackToCachedValue}：该场景行为改动即失败。
     */
    @Test
    void unsupportedFormulaShouldFallBackToCachedValue() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("S1");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("合计");
            Cell formula = row.createCell(1);
            // POI 求值不支持 WEBSERVICE，文件里的缓存值 7 是唯一可用来源
            formula.setCellFormula("WEBSERVICE(\"http://example.com\")");
            formula.setCellValue(7);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);

            ParseSource source = parser.parse(context(out.toByteArray(), MIME_XLSX));

            assertTrue(cellTexts(source).contains("7"), () -> "cells=" + cellTexts(source));
            assertTrue(cellTexts(source).stream().noneMatch(text -> text.contains("WEBSERVICE")),
                    () -> "cells=" + cellTexts(source));
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
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);

            ParseSource source = parser.parse(context(out.toByteArray(), MIME_XLSX));

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
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);

            ParseSource source = parser.parse(context(out.toByteArray(), MIME_XLSX));

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

    /**
     * 守护 {@code supportsShouldMatchExcelFormats}：该场景行为改动即失败。
     */
    @Test
    void supportsShouldMatchExcelFormats() {
        assertTrue(parser.supports(MIME_XLS));
        assertTrue(parser.supports(MIME_XLSX));
        assertFalse(parser.supports("application/msword"));
        assertFalse(parser.supports("application/pdf"));
        assertEquals("poi", parser.capabilityName());
        assertEquals("5.4.0", parser.capabilityVersion());
    }
}
