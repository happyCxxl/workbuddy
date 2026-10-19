package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.capability.TableCell;
import com.workbuddy.common.domain.parse.capability.TableResult;
import com.workbuddy.common.enums.parse.ElementType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构识别结果回填单测：行列数与每格 row/col span 按模型结论写入，表头行取表头格最多的行，
 * 格级置信度落到单元格元素上；结果不完整时返回 null 走内置降级。
 */
class TableStructureSupportTest {

    /** 模型结论：首行横向合并的表头 + 数据行 → 表头行 0、跨度与置信度回填（{@code tableResultShouldBackfillHeaderRowAndSpans}）。 */
    @Test
    void tableResultShouldBackfillHeaderRowAndSpans() {
        TableResult result = new TableResult();
        result.setRows(2);
        result.setCols(3);
        result.setCells(List.of(
                cell(0, 0, null, 3, "评分表", true, 0.96, new BBox(72, 100, 300, 20)),
                cell(1, 0, null, null, "A1", false, 0.91, new BBox(72, 120, 100, 20)),
                cell(1, 1, 1, 2, "合并格", false, 0.88, new BBox(172, 120, 200, 20))));

        ParseElement table = TableStructureSupport.toTableElement(result, 3);

        assertNotNull(table);
        assertEquals(ElementType.TABLE.name(), table.getType());
        assertEquals(3, table.getPage());
        assertEquals(2, table.getRows());
        assertEquals(3, table.getCols());
        assertEquals(0, table.getHeaderRow());
        assertEquals(3, table.getCells().size());
        assertEquals(3, table.getCells().getFirst().getColSpan());
        assertNull(table.getCells().get(1).getColSpan(), "单跨不写字段");
        assertNull(table.getCells().get(2).getRowSpan(), "单跨不写字段");
        assertEquals(2, table.getCells().get(2).getColSpan());
        assertEquals(0.96, table.getCells().getFirst().getConfidence(), 0.0001);
        assertTrue(table.getCells().getFirst().getIsHeader());
        // 表包围盒取各格并集
        assertEquals(72.0, table.getBbox().getX(), 0.0001);
        assertEquals(300.0, table.getBbox().getWidth(), 0.0001);
        assertEquals(40.0, table.getBbox().getHeight(), 0.0001);
    }

    /** 模型没给表头标记：表头行为 null（不硬猜）（{@code missingHeaderFlagShouldLeaveHeaderRowNull}）。 */
    @Test
    void missingHeaderFlagShouldLeaveHeaderRowNull() {
        TableResult result = new TableResult();
        result.setRows(1);
        result.setCols(1);
        result.setCells(List.of(cell(0, 0, null, null, "只有一格", false, null, null)));

        ParseElement table = TableStructureSupport.toTableElement(result, 1);

        assertNotNull(table);
        assertNull(table.getHeaderRow());
        assertNull(table.getBbox());
    }

    /** 结果不完整：行列或格子缺失时返回 null，走内置降级（{@code incompleteResultShouldReturnNull}）。 */
    @Test
    void incompleteResultShouldReturnNull() {
        assertNull(TableStructureSupport.toTableElement(null, 1));

        TableResult withoutCells = new TableResult();
        withoutCells.setRows(2);
        withoutCells.setCols(2);
        assertNull(TableStructureSupport.toTableElement(withoutCells, 1));

        TableResult withoutGrid = new TableResult();
        withoutGrid.setCells(List.of(cell(0, 0, null, null, "x", false, null, null)));
        assertNull(TableStructureSupport.toTableElement(withoutGrid, 1));
    }

    private TableCell cell(int row, int col, Integer rowSpan, Integer colSpan, String text, boolean isHeader,
                           Double confidence, BBox bbox) {
        TableCell cell = new TableCell();
        cell.setRow(row);
        cell.setCol(col);
        cell.setRowSpan(rowSpan);
        cell.setColSpan(colSpan);
        cell.setText(text);
        cell.setIsHeader(isHeader);
        cell.setConfidence(confidence);
        cell.setBbox(bbox);
        return cell;
    }
}
