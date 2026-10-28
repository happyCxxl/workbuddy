package com.workbuddy.worker.parser.office.impl;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ElementType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DOCX 表格与段落解析单测：表格结构、横向/纵向合并、段落字体与能力标识。
 */
class DocxTableParserTest {

    private final DocxTestFixture fixture = new DocxTestFixture();

    /**
     * 守护 {@code docxShouldParseParagraphsTableAndMerges}：该场景行为改动即失败。
     */
    @Test
    void docxShouldParseParagraphsTableAndMerges() throws Exception {
        ParseSource source = fixture.parse(fixture.buildDocx());

        assertEquals(1, source.getUnitCount());
        List<ParseElement> elements = source.getElements();
        ParseElement title = elements.stream()
                .filter(e -> "第一章 投标人须知".equals(e.getText()))
                .findFirst().orElse(null);
        assertNotNull(title);
        assertEquals(ElementType.PARAGRAPH.name(), title.getType());
        assertEquals(16.0, title.getFont().getSize());
        assertTrue(title.getFont().getBold());
        assertEquals("office#document.xml/paragraph[0]", title.getProvenance().getPath());

        ParseElement table = elements.stream()
                .filter(e -> ElementType.TABLE.name().equals(e.getType()))
                .findFirst().orElse(null);
        assertNotNull(table);
        assertEquals(2, table.getRows());
        assertEquals(4, table.getCols());
        assertEquals(0, table.getHeaderRow());
        // 7 个单元格：(0,0)(0,1 跨 2 列)(0,3)(1,0..3)；合并格之后的真实单元格不丢
        assertEquals(7, table.getCells().size());
        ParseElement merged = table.getCells().stream()
                .filter(c -> c.getRow() == 0 && c.getCol() == 1)
                .findFirst().orElse(null);
        assertNotNull(merged);
        assertEquals(2, merged.getColSpan());
        assertEquals("备注", cellText(table, 0, 3));
        assertEquals("30", cellText(table, 1, 2));
        assertEquals("评分表", cellText(table, 1, 3));
    }

    /** vMerge=continue 格自带文本：并入起始格（换行分隔），不丢（{@code continueCellTextShouldBeMergedIntoRestartCell}）。 */
    @Test
    void continueCellTextShouldBeMergedIntoRestartCell() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFTable table = doc.createTable(3, 2);
            table.getRow(0).getCell(0).setText("评分项");
            table.getRow(0).getCell(1).setText("分值");
            table.getRow(1).getCell(0).setText("A1 报价");
            table.getRow(1).getCell(1).setText("30");
            tcPr(table.getRow(1).getCell(0)).addNewVMerge().setVal(STMerge.RESTART);
            table.getRow(2).getCell(0).setText("续接文本");
            table.getRow(2).getCell(1).setText("15");
            tcPr(table.getRow(2).getCell(0)).addNewVMerge().setVal(STMerge.CONTINUE);

            ParseSource source = fixture.parse(DocxTestFixture.save(doc));

            ParseElement tableElement = source.getElements().stream()
                    .filter(e -> ElementType.TABLE.name().equals(e.getType()))
                    .findFirst().orElseThrow();
            // 单元格嵌在 TABLE 元素内部，不在顶层元素列表里
            ParseElement anchor = tableElement.getCells().stream()
                    .filter(cell -> Integer.valueOf(1).equals(cell.getRow())
                            && Integer.valueOf(0).equals(cell.getCol()))
                    .findFirst().orElse(null);
            assertNotNull(anchor, () -> "cells=" + tableElement.getCells().stream()
                    .map(cell -> cell.getRow() + "," + cell.getCol() + "=" + cell.getText()).toList());
            assertTrue(anchor.getText().contains("A1 报价"), () -> "text=" + anchor.getText());
            assertTrue(anchor.getText().contains("续接文本"), () -> "text=" + anchor.getText());
            assertEquals(2, anchor.getRowSpan());
        }
    }

    /**
     * 段落序号取源文档序号：中间的空段不产元素但仍占号，元素序号不错位 ——
     * 预览按「段落序号」定位的前提（判定单元另有字符折算的虚拟页口径）。
     * <p>守护 {@code paragraphOrdinalShouldFollowSourceDocument}：该场景行为改动即失败。
     */
    @Test
    void paragraphOrdinalShouldFollowSourceDocument() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("第一段");
            doc.createParagraph();
            doc.createParagraph().createRun().setText("第三段");

            ParseSource source = fixture.parse(DocxTestFixture.save(doc));

            List<String> paths = source.getElements().stream()
                    .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                    .map(e -> e.getProvenance().getPath())
                    .toList();
            assertEquals(List.of("office#document.xml/paragraph[0]", "office#document.xml/paragraph[2]"), paths);
        }
    }

    /**
     * 守护 {@code docxVerticalMergeShouldFillRowSpan}：该场景行为改动即失败。
     */
    @Test
    void docxVerticalMergeShouldFillRowSpan() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFTable table = doc.createTable(3, 2);
            table.getRow(0).getCell(0).setText("评分项");
            table.getRow(0).getCell(1).setText("分值");
            table.getRow(1).getCell(0).setText("A1 报价");
            table.getRow(1).getCell(1).setText("30");
            // 第二行起纵向合并首列：行 1 restart、行 2 continue（continue 格不产出元素）
            tcPr(table.getRow(1).getCell(0)).addNewVMerge().setVal(STMerge.RESTART);
            table.getRow(2).getCell(1).setText("15");
            tcPr(table.getRow(2).getCell(0)).addNewVMerge().setVal(STMerge.CONTINUE);

            ParseSource source = fixture.parse(DocxTestFixture.save(doc));

            ParseElement tableElement = source.getElements().stream()
                    .filter(e -> ElementType.TABLE.name().equals(e.getType()))
                    .findFirst().orElse(null);
            assertNotNull(tableElement);
            assertEquals(3, tableElement.getRows());
            assertEquals(2, tableElement.getCols());
            ParseElement mergedCell = cell(tableElement, 1, 0);
            assertNotNull(mergedCell);
            assertEquals(2, mergedCell.getRowSpan());
            assertNull(cell(tableElement, 2, 0));
            assertEquals("15", cellText(tableElement, 2, 1));
        }
    }

    /** 取指定行列的单元格文本（不存在记 null） */
    private String cellText(ParseElement table, int row, int col) {
        ParseElement cell = cell(table, row, col);
        return cell == null ? null : cell.getText();
    }

    /** 取指定行列的单元格元素（不存在记 null） */
    private ParseElement cell(ParseElement table, int row, int col) {
        return table.getCells().stream()
                .filter(c -> c.getRow() == row && c.getCol() == col)
                .findFirst().orElse(null);
    }

    /** 复用或新建单元格属性块 */
    private CTTcPr tcPr(XWPFTableCell cell) {
        return cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
    }

    /**
     * 守护 {@code paragraphFontShouldComeFromFirstRunWithFontFacts}：该场景行为改动即失败。
     */
    @Test
    void paragraphFontShouldComeFromFirstRunWithFontFacts() throws Exception {
        byte[] data;
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph paragraph = doc.createParagraph();
            XWPFRun plain = paragraph.createRun();
            plain.setText("无字体事实的 run");
            XWPFRun styled = paragraph.createRun();
            styled.setText("带字体事实的 run");
            styled.setFontSize(14.0);
            data = DocxTestFixture.save(doc);
        }

        ParseSource source = fixture.parse(data);

        ParseElement paragraph = source.getElements().stream()
                .filter(e -> ElementType.PARAGRAPH.name().equals(e.getType()))
                .findFirst().orElse(null);
        assertNotNull(paragraph);
        assertNotNull(paragraph.getFont());
        assertEquals(14.0, paragraph.getFont().getSize(), () -> "font=" + paragraph.getFont());
    }

    /**
     * 守护 {@code supportsShouldMatchDocxOnly}：该场景行为改动即失败。
     */
    @Test
    void supportsShouldMatchDocxOnly() {
        assertTrue(fixture.parser().supports(DocxTestFixture.MIME_DOCX));
        assertFalse(fixture.parser().supports("application/msword"));
        assertFalse(fixture.parser().supports("application/vnd.ms-excel"));
        assertFalse(fixture.parser().supports("application/pdf"));
        assertEquals("poi", fixture.parser().capabilityName());
        assertEquals("5.4.0", fixture.parser().capabilityVersion());
    }
}
