package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ElementType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同页同 bbox 重复表合并：并集单元格、冲突片段保留双方文本、不同 bbox 不合并。
 */
class TableDeduplicatorTest {

    /**
     * 守护 {@code sameBboxTablesShouldMerge}：同 bbox 两张部分表合并成一张完整表。
     */
    @Test
    void sameBboxTablesShouldMerge() {
        ParseSource source = ParseSource.nativeSource("test");
        List<ParseElement> elements = new ArrayList<>();
        elements.add(table(1, 100, 100, 200, 80,
                cells(new int[][]{{0, 0}, {1, 0}}, new String[]{"序号", "1"})));
        elements.add(table(1, 100, 100, 200, 80,
                cells(new int[][]{{0, 1}, {1, 1}}, new String[]{"名称", "图书"})));

        TableDeduplicator.mergeInPlace(elements, source, 1);

        assertEquals(1, elements.size(), () -> "elements=" + elements);
        ParseElement merged = elements.getFirst();
        assertEquals(2, merged.getRows());
        assertEquals(2, merged.getCols());
        assertEquals(4, merged.getCells().size());
        assertTrue(merged.getCells().stream().anyMatch(cell -> "序号".equals(cell.getText())));
        assertTrue(merged.getCells().stream().anyMatch(cell -> "图书".equals(cell.getText())));
    }

    /**
     * 守护 {@code conflictingCellShouldKeepBothFragments}：同一格两个片段都要保留并写冲突事实。
     */
    @Test
    void conflictingCellShouldKeepBothFragments() {
        ParseSource source = ParseSource.nativeSource("test");
        List<ParseElement> elements = new ArrayList<>();
        elements.add(table(1, 100, 100, 200, 80,
                cells(new int[][]{{1, 2}}, new String[]{"□中型企业"})));
        elements.add(table(1, 100, 100, 200, 80,
                cells(new int[][]{{1, 2}}, new String[]{"（选择）"})));

        TableDeduplicator.mergeInPlace(elements, source, 1);

        assertEquals(1, elements.size());
        String text = elements.getFirst().getCells().getFirst().getText();
        assertTrue(text.contains("□中型企业") && text.contains("（选择）"), () -> "text=" + text);
        assertTrue(source.getFacts().stream()
                        .anyMatch(fact -> fact.getEvidence() != null && fact.getEvidence().contains("冲突 1")),
                () -> "facts=" + source.getFacts());
    }

    /**
     * 守护 {@code differentBboxShouldStaySeparate}：bbox 明显不同的两张表不能合并。
     */
    @Test
    void differentBboxShouldStaySeparate() {
        ParseSource source = ParseSource.nativeSource("test");
        List<ParseElement> elements = new ArrayList<>();
        elements.add(table(1, 100, 100, 200, 80,
                cells(new int[][]{{0, 0}}, new String[]{"A"})));
        elements.add(table(1, 100, 200, 200, 80,
                cells(new int[][]{{0, 0}}, new String[]{"B"})));

        TableDeduplicator.mergeInPlace(elements, source, 1);

        assertEquals(2, elements.size(), () -> "elements=" + elements);
    }

    /** 造一张表 */
    private static ParseElement table(int page, double x, double y, double width, double height,
                                      List<ParseElement> cells) {
        ParseElement table = ParseElement.of(ElementType.TABLE);
        table.setPage(page);
        table.setBbox(new BBox(x, y, width, height));
        table.setRows(2);
        table.setCols(2);
        table.setCells(cells);
        return table;
    }

    /** 按行列造单元格 */
    private static List<ParseElement> cells(int[][] positions, String[] texts) {
        List<ParseElement> cells = new ArrayList<>();
        for (int i = 0; i < positions.length; i++) {
            ParseElement cell = ParseElement.of(ElementType.TABLE_CELL);
            cell.setRow(positions[i][0]);
            cell.setCol(positions[i][1]);
            cell.setText(texts[i]);
            cell.setBbox(new BBox(100, 100, 100, 40));
            cells.add(cell);
        }
        return cells;
    }
}
