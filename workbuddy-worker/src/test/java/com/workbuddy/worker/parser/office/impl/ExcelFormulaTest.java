package com.workbuddy.worker.parser.office.impl;

import com.workbuddy.common.domain.parse.ParseSource;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 公式解析单测：缓存值优先、无缓存求值、求值失败回退与旧格式提示。
 */
class ExcelFormulaTest {

    private final ExcelTestFixture fixture = new ExcelTestFixture();

    /** .xls 公式缓存不可辨别：有公式格时按档在路说明里留痕，取值仍按缓存值（{@code xlsFormulaCacheShouldBeNoted}）。 */
    @Test
    void xlsFormulaCacheShouldBeNoted() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("S1");
            sheet.createRow(0).createCell(0).setCellValue("指标");
            sheet.createRow(1).createCell(0).setCellFormula("1+1");

            ParseSource source = fixture.parse(ExcelTestFixture.save(workbook), ExcelTestFixture.MIME_XLS);

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

            ParseSource source = fixture.parse(ExcelTestFixture.save(workbook), ExcelTestFixture.MIME_XLSX);

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

            ParseSource source = fixture.parse(ExcelTestFixture.save(workbook), ExcelTestFixture.MIME_XLSX);

            assertTrue(ExcelTestFixture.cellTexts(source).contains("7"),
                    () -> "cells=" + ExcelTestFixture.cellTexts(source));
            assertFalse(ExcelTestFixture.cellTexts(source).contains("2"),
                    () -> "cells=" + ExcelTestFixture.cellTexts(source));
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

            ParseSource source = fixture.parse(ExcelTestFixture.save(workbook), ExcelTestFixture.MIME_XLSX);

            assertTrue(ExcelTestFixture.cellTexts(source).contains("5"),
                    () -> "cells=" + ExcelTestFixture.cellTexts(source));
            assertTrue(ExcelTestFixture.cellTexts(source).stream().noneMatch(text -> text.contains("SUM")),
                    () -> "cells=" + ExcelTestFixture.cellTexts(source));
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

            ParseSource source = fixture.parse(ExcelTestFixture.save(workbook), ExcelTestFixture.MIME_XLSX);

            assertTrue(ExcelTestFixture.cellTexts(source).contains("3"),
                    () -> "cells=" + ExcelTestFixture.cellTexts(source));
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

            ParseSource source = fixture.parse(ExcelTestFixture.save(workbook), ExcelTestFixture.MIME_XLSX);

            assertTrue(ExcelTestFixture.cellTexts(source).contains("7"),
                    () -> "cells=" + ExcelTestFixture.cellTexts(source));
            assertTrue(ExcelTestFixture.cellTexts(source).stream()
                            .noneMatch(text -> text.contains("WEBSERVICE")),
                    () -> "cells=" + ExcelTestFixture.cellTexts(source));
        }
    }
}
