package com.workbuddy.worker.parser.office;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.enums.parse.ElementType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表头行判定口径单测（DOC / DOCX 共用助手）：首行有非空文本即判第 0 行为表头行，
 * 首行全空则给 null；单元格 isHeader 收敛为该判定。
 */
class PoiTableHeaderSupportTest {

    /** 首行有文本：判表头行 0，且只有第 0 行置 isHeader（{@code firstRowWithTextShouldBeHeaderRow}）。 */
    @Test
    void firstRowWithTextShouldBeHeaderRow() {
        ParseElement table = table(cell(0, 0, "评分项"), cell(0, 1, "分值"), cell(1, 0, "A1"), cell(1, 1, "30"));

        Integer headerRow = AbstractPoiDocumentParser.settleHeaderRow(table);

        assertEquals(0, headerRow);
        assertTrue(table.getCells().stream()
                .filter(c -> Integer.valueOf(0).equals(c.getRow())).allMatch(ParseElement::getIsHeader));
        assertFalse(table.getCells().stream()
                .filter(c -> Integer.valueOf(1).equals(c.getRow())).anyMatch(ParseElement::getIsHeader));
    }

    /** 首行全空：不判表头，headerRow 为 null，且没有任何单元格标表头（{@code blankFirstRowShouldGiveNullHeaderRow}）。 */
    @Test
    void blankFirstRowShouldGiveNullHeaderRow() {
        ParseElement table = table(cell(0, 0, "  "), cell(0, 1, null), cell(1, 0, "A1"), cell(1, 1, "30"));

        Integer headerRow = AbstractPoiDocumentParser.settleHeaderRow(table);

        assertNull(headerRow);
        assertFalse(table.getCells().stream().anyMatch(ParseElement::getIsHeader));
    }

    /** 空表（无单元格）：不判表头，不抛异常（{@code emptyTableShouldGiveNullHeaderRow}）。 */
    @Test
    void emptyTableShouldGiveNullHeaderRow() {
        ParseElement table = ParseElement.of(ElementType.TABLE);

        assertNull(AbstractPoiDocumentParser.settleHeaderRow(table));
    }

    private ParseElement table(ParseElement... cells) {
        ParseElement table = ParseElement.of(ElementType.TABLE);
        for (ParseElement cell : cells) {
            AbstractPoiDocumentParser.appendCell(table, cell);
        }
        return table;
    }

    private ParseElement cell(int row, int col, String text) {
        ParseElement cell = ParseElement.of(ElementType.TABLE_CELL);
        cell.setRow(row);
        cell.setCol(col);
        cell.setText(text);
        return cell;
    }
}
