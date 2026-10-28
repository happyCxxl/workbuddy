package com.workbuddy.worker.parser.office.impl;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ElementType;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 结构解析单测：表头/列数口径、合并区域、页眉页脚、XLS 同路径与能力标识。
 */
class ExcelStructureParserTest {

    private final ExcelTestFixture fixture = new ExcelTestFixture();

    /** 首行为空（行对象存在但无单元格）：cols 按实际数据算，不出负数也不抛异常（{@code emptyFirstRowShouldStillGiveDataColumns}）。 */
    @Test
    void emptyFirstRowShouldStillGiveDataColumns() throws Exception {
        ParseSource source = fixture.parse(buildXlsxWithEmptyFirstRow(), ExcelTestFixture.MIME_XLSX);

        ParseElement table = source.getElements().stream()
                .filter(element -> ElementType.TABLE.name().equals(element.getType())).findFirst().orElseThrow();
        assertEquals(2, table.getCols(), () -> "cols=" + table.getCols());
        // 行数按真实行号区间算：空的首行也占一号，单元格 row 索引保持 1、2
        assertEquals(3, table.getRows());
        assertTrue(ExcelTestFixture.cellTexts(source).contains("A1 报价"),
                () -> "cells=" + ExcelTestFixture.cellTexts(source));
        assertTrue(ExcelTestFixture.cellTexts(source).contains("分值"),
                () -> "cells=" + ExcelTestFixture.cellTexts(source));
    }

    /** 承接列数口径：数据区比首行宽时，cols 取数据最大列，不被首行截短（{@code narrowerFirstRowShouldNotTruncateColumns}）。 */
    @Test
    void narrowerFirstRowShouldNotTruncateColumns() throws Exception {
        ParseSource source = fixture.parse(buildXlsxWithNarrowTitleRow(), ExcelTestFixture.MIME_XLSX);

        ParseElement table = source.getElements().stream()
                .filter(element -> ElementType.TABLE.name().equals(element.getType())).findFirst().orElseThrow();
        assertEquals(3, table.getCols(), () -> "cols=" + table.getCols());
    }

    /** 合并区被覆盖格带文本：并入锚格（换行分隔），不丢（{@code coveredCellTextShouldBeMergedIntoAnchor}）。 */
    @Test
    void coveredCellTextShouldBeMergedIntoAnchor() throws Exception {
        ParseSource source = fixture.parse(buildXlsxWithCoveredText(), ExcelTestFixture.MIME_XLSX);

        List<String> texts = ExcelTestFixture.cellTexts(source);
        assertTrue(texts.stream().anyMatch(text -> text.contains("合并块") && text.contains("被覆盖值")),
                () -> "cells=" + texts);
    }

    /** 页眉页脚产元素：三区拼接成一条 HEADER、页脚一条 FOOTER，格式码被清掉（{@code sheetHeaderFooterShouldEmitElements}）。 */
    @Test
    void sheetHeaderFooterShouldEmitElements() throws Exception {
        ParseSource source = fixture.parse(buildXlsxWithHeaderFooter(), ExcelTestFixture.MIME_XLSX);

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

    /** 首行空时表头行指向真实数据首行（第 1 行），isHeader 只标该行（{@code headerRowShouldPointToFirstDataRow}）。 */
    @Test
    void headerRowShouldPointToFirstDataRow() throws Exception {
        ParseSource source = fixture.parse(buildXlsxWithEmptyFirstRow(), ExcelTestFixture.MIME_XLSX);

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
            ParseSource source = fixture.parse(ExcelTestFixture.save(workbook), ExcelTestFixture.MIME_XLS);
            assertEquals(1, source.getUnitCount());
            assertEquals(1, source.getElements().size());
            assertTrue(source.getElements().getFirst().getCells().size() >= 2);
        }
    }

    /**
     * 守护 {@code supportsShouldMatchExcelFormats}：该场景行为改动即失败。
     */
    @Test
    void supportsShouldMatchExcelFormats() {
        assertTrue(fixture.parser().supports(ExcelTestFixture.MIME_XLS));
        assertTrue(fixture.parser().supports(ExcelTestFixture.MIME_XLSX));
        assertFalse(fixture.parser().supports("application/msword"));
        assertFalse(fixture.parser().supports("application/pdf"));
        assertEquals("poi", fixture.parser().capabilityName());
        assertEquals("5.4.0", fixture.parser().capabilityVersion());
    }

    /**
     * 守护 {@code xlsxShouldParseSheetTableWithMerges}：该场景行为改动即失败。
     */
    @Test
    void xlsxShouldParseSheetTableWithMerges() throws Exception {
        ParseSource source = fixture.parse(buildXlsx(), ExcelTestFixture.MIME_XLSX);

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
            return ExcelTestFixture.save(workbook);
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
            return ExcelTestFixture.save(workbook);
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
            return ExcelTestFixture.save(workbook);
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
            return ExcelTestFixture.save(workbook);
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
            return ExcelTestFixture.save(workbook);
        }
    }
}
