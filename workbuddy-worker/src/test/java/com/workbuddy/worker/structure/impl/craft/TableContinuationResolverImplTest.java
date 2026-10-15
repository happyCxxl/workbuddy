package com.workbuddy.worker.structure.impl.craft;
import com.workbuddy.common.enums.structure.UnifiedElementType;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.worker.structure.AssembleContext;
import com.workbuddy.worker.structure.StructureProperties;
import com.workbuddy.worker.structure.craft.ContinuationOutcome;
import com.workbuddy.worker.structure.impl.StructureJudgeRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨页续表接续单测：四条件主规则 / 放宽规则疑似 / 非相邻页不接续。
 *
 * @author cxxl
 */
class TableContinuationResolverImplTest {

    private TableContinuationResolverImpl resolver;
    private StructureProperties properties;

    @BeforeEach
    void setUp() {
        properties = new StructureProperties();
        resolver = new TableContinuationResolverImpl(new StructureJudgeRegistry(new ArrayList<>(), properties));
    }

    private AssembleContext context() {
        AssembleContext context = new AssembleContext();
        context.setProperties(properties);
        return context;
    }

    private UnifiedElement table(int page, double y, boolean cutAtBottom, String... headerTexts) {
        UnifiedElement table = new UnifiedElement();
        table.setId("t-" + page + "-" + Math.abs(java.util.Arrays.hashCode(headerTexts)));
        table.setType(UnifiedElementType.TABLE.name());
        table.setPage(page);
        table.setBbox(new BBox(72, y, 400, 100));
        table.setCols(headerTexts.length);
        table.setHeaderRow(0);
        table.setExtension(Map.of("cutAtPageBottom", cutAtBottom));
        List<UnifiedElement> cells = new ArrayList<>();
        for (int i = 0; i < headerTexts.length; i++) {
            UnifiedElement cell = new UnifiedElement();
            cell.setId("tc-" + page + "-" + i);
            cell.setType(UnifiedElementType.TABLE_CELL.name());
            cell.setRow(0);
            cell.setCol(i);
            cell.setIsHeader(true);
            cell.setText(headerTexts[i]);
            cell.setBbox(new BBox(72 + i * 100, y, 100, 20));
            cells.add(cell);
        }
        table.setCells(cells);
        table.setRows(2);
        return table;
    }

    @Test
    void fourConditionsShouldMergeAndInheritHeader() {
        UnifiedElement a = table(2, 700, true, "评分项", "评分标准", "分值");
        UnifiedElement b = table(3, 20, false, "评分项", "评分标准", "分值");

        ContinuationOutcome outcome = resolver.joinContinuations(new ArrayList<>(List.of(a, b)), context());

        assertEquals(1, outcome.getElements().size());
        assertEquals(1, outcome.getContinuationCount());
        assertEquals(0, outcome.getSuspectedCount());
        UnifiedElement merged = outcome.getElements().getFirst();
        assertTrue(merged.getHeaderInherited());
        assertEquals(List.of(2, 3), merged.getPageRange());
        assertEquals(2, merged.getBboxes().size());
        assertTrue(outcome.getRelations().stream().anyMatch(r -> "CONTINUATION_OF".equals(r.getType())));
    }

    @Test
    void relaxedRuleShouldMergeAsSuspected() {
        UnifiedElement a = table(2, 700, true, "评分项", "评分标准", "分值");
        UnifiedElement b = table(3, 20, false, "B1 售后承诺", "质保期不少于 3 年", "10");

        ContinuationOutcome outcome = resolver.joinContinuations(new ArrayList<>(List.of(a, b)), context());

        assertEquals(1, outcome.getElements().size());
        assertEquals(1, outcome.getContinuationCount());
        assertEquals(1, outcome.getSuspectedCount());
        UnifiedElement merged = outcome.getElements().getFirst();
        assertTrue(merged.getHeaderInherited());
    }

    @Test
    void nonAdjacentPagesShouldNotMerge() {
        UnifiedElement a = table(2, 700, true, "评分项", "评分标准", "分值");
        UnifiedElement b = table(5, 20, false, "评分项", "评分标准", "分值");

        ContinuationOutcome outcome = resolver.joinContinuations(new ArrayList<>(List.of(a, b)), context());

        assertEquals(2, outcome.getElements().size());
        assertEquals(0, outcome.getContinuationCount());
    }

    @Test
    void threePageTableShouldMergeIntoOneChain() {
        UnifiedElement a = table(2, 700, true, "评分项", "评分标准", "分值");
        UnifiedElement b = table(3, 20, true, "评分项", "评分标准", "分值");
        UnifiedElement c = table(4, 20, false, "评分项", "评分标准", "分值");

        ContinuationOutcome outcome = resolver.joinContinuations(new ArrayList<>(List.of(a, b, c)), context());

        assertEquals(1, outcome.getElements().size());
        assertEquals(2, outcome.getContinuationCount());
        assertEquals(0, outcome.getSuspectedCount());
        UnifiedElement merged = outcome.getElements().getFirst();
        assertEquals(List.of(2, 3, 4), merged.getPageRange());
        assertEquals(3, merged.getBboxes().size());
        assertEquals(2, merged.getPage());
        assertNotNull(merged.getBbox());
        assertEquals(4, merged.getRows());
        assertEquals(3, merged.getCells().size());
    }

    @Test
    void chainedRelationShouldCarryRealPageNumbers() {
        UnifiedElement a = table(2, 700, true, "评分项", "评分标准", "分值");
        UnifiedElement b = table(3, 20, true, "评分项", "评分标准", "分值");
        UnifiedElement c = table(4, 20, false, "评分项", "评分标准", "分值");

        String firstId = a.getId();
        ContinuationOutcome outcome = resolver.joinContinuations(new ArrayList<>(List.of(a, b, c)), context());

        List<String> relations = outcome.getRelations().stream()
                .filter(r -> "CONTINUATION_OF".equals(r.getType()))
                .map(r -> r.getFrom() + "->" + r.getTo())
                .toList();
        assertEquals(List.of(firstId + "#p3->" + firstId + "#p2", firstId + "#p4->" + firstId + "#p2"), relations);
    }

    @Test
    void chainShouldStopAtNonAdjacentPage() {
        UnifiedElement a = table(2, 700, true, "评分项", "评分标准", "分值");
        UnifiedElement b = table(3, 20, true, "评分项", "评分标准", "分值");
        UnifiedElement c = table(6, 20, false, "评分项", "评分标准", "分值");

        ContinuationOutcome outcome = resolver.joinContinuations(new ArrayList<>(List.of(a, b, c)), context());

        assertEquals(2, outcome.getElements().size());
        assertEquals(1, outcome.getContinuationCount());
        assertEquals(List.of(2, 3), outcome.getElements().getFirst().getPageRange());
    }

    /** 多行表格：headerRowNumber 为 null 表示表头未判定 */
    private UnifiedElement multiRowTable(int page, double y, boolean cutAtBottom, Integer headerRowNumber,
                                         List<List<String>> rows) {
        UnifiedElement table = new UnifiedElement();
        table.setId("t-" + page);
        table.setType(UnifiedElementType.TABLE.name());
        table.setPage(page);
        table.setBbox(new BBox(72, y, 400, 100));
        table.setCols(rows.getFirst().size());
        table.setHeaderRow(headerRowNumber);
        table.setRows(rows.size());
        table.setExtension(Map.of("cutAtPageBottom", cutAtBottom));
        List<UnifiedElement> cells = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < rows.get(r).size(); c++) {
                UnifiedElement cell = new UnifiedElement();
                cell.setId("tc-" + page + "-" + r + "-" + c);
                cell.setType(UnifiedElementType.TABLE_CELL.name());
                cell.setRow(r);
                cell.setCol(c);
                cell.setIsHeader(headerRowNumber != null && r == headerRowNumber);
                cell.setText(rows.get(r).get(c));
                cell.setBbox(new BBox(72 + c * 100, y + r * 20, 100, 20));
                cells.add(cell);
            }
        }
        table.setCells(cells);
        return table;
    }

    private UnifiedElement cellOf(UnifiedElement table, int row, int col) {
        return table.getCells().stream()
                .filter(c -> Integer.valueOf(row).equals(c.getRow()) && Integer.valueOf(col).equals(c.getCol()))
                .findFirst().orElse(null);
    }

    @Test
    void noHeaderContinuationShouldRenumberRowsWithoutOverlap() {
        // 两侧表头都判不出：空对空相似度命中主规则 → 合并；B 没有可丢弃的表头行
        UnifiedElement a = multiRowTable(2, 700, true, null,
                List.of(List.of("甲", "10"), List.of("乙", "20"), List.of("丙", "30")));
        UnifiedElement b = multiRowTable(3, 20, false, null,
                List.of(List.of("丁", "40"), List.of("戊", "50")));

        ContinuationOutcome outcome = resolver.joinContinuations(new ArrayList<>(List.of(a, b)), context());

        assertEquals(1, outcome.getElements().size());
        UnifiedElement merged = outcome.getElements().getFirst();
        assertEquals(5, merged.getRows());
        assertNotNull(cellOf(merged, 3, 0));
        assertEquals("丁", cellOf(merged, 3, 0).getText());
        assertEquals("戊", cellOf(merged, 4, 0).getText());
        Set<String> positions = merged.getCells().stream()
                .map(c -> c.getRow() + "-" + c.getCol())
                .collect(Collectors.toSet());
        assertEquals(merged.getCells().size(), positions.size()); // 无 (row,col) 重叠
    }

    @Test
    void headerRowZeroContinuationShouldKeepRowNumbering() {
        UnifiedElement a = multiRowTable(2, 700, true, 0,
                List.of(List.of("评分项", "分值"), List.of("甲", "10"), List.of("乙", "20")));
        UnifiedElement b = multiRowTable(3, 20, false, 0,
                List.of(List.of("评分项", "分值"), List.of("丙", "30"), List.of("丁", "40")));

        ContinuationOutcome outcome = resolver.joinContinuations(new ArrayList<>(List.of(a, b)), context());

        UnifiedElement merged = outcome.getElements().getFirst();
        assertEquals(5, merged.getRows());
        assertEquals("丙", cellOf(merged, 3, 0).getText());
        assertEquals("丁", cellOf(merged, 4, 0).getText());
        assertTrue(merged.getCells().stream().noneMatch(c -> "tc-3-0-0".equals(c.getId()))); // B 表头行已丢弃
    }

    @Test
    void headerRowNotFirstShouldDropOnlyHeaderRow() {
        UnifiedElement a = multiRowTable(2, 700, true, 0,
                List.of(List.of("评分项", "分值"), List.of("甲", "10")));
        UnifiedElement b = multiRowTable(3, 20, false, 1,
                List.of(List.of("说明", "备注"), List.of("评分项", "分值"), List.of("乙", "20")));

        ContinuationOutcome outcome = resolver.joinContinuations(new ArrayList<>(List.of(a, b)), context());

        assertEquals(1, outcome.getElements().size());
        UnifiedElement merged = outcome.getElements().getFirst();
        assertEquals(4, merged.getRows()); // A 两行 + B 保留两行（其表头行在第 1 行）
        assertEquals("说明", cellOf(merged, 2, 0).getText());
        assertEquals("乙", cellOf(merged, 3, 0).getText());
        assertTrue(merged.getCells().stream().noneMatch(c -> "tc-3-1-0".equals(c.getId())));
    }

    @Test
    void missingRowsFieldShouldFallBackToCellRows() {
        UnifiedElement a = multiRowTable(2, 700, true, 0,
                List.of(List.of("评分项", "分值"), List.of("甲", "10")));
        a.setRows(null);
        UnifiedElement b = multiRowTable(3, 20, false, 0,
                List.of(List.of("评分项", "分值"), List.of("乙", "20")));

        ContinuationOutcome outcome = resolver.joinContinuations(new ArrayList<>(List.of(a, b)), context());

        UnifiedElement merged = outcome.getElements().getFirst();
        assertEquals(3, merged.getRows()); // A 侧按单元格最大行号 + 1 = 2，B 保留一行
        assertEquals("乙", cellOf(merged, 2, 0).getText());
    }

    /** 统一改写各列宽度（构造"列宽不一致"的对照表） */
    private void setColumnWidth(UnifiedElement table, double width) {
        for (UnifiedElement cell : table.getCells()) {
            cell.setBbox(new BBox(72 + cell.getCol() * width, cell.getBbox().getY(), width,
                    cell.getBbox().getHeight()));
        }
    }

    @Test
    void unreadableHeadersShouldMergeAsSuspectedWhenWidthsMatch() {
        // 两侧表头都判不出：不再按"一致"走主规则，改由列宽模式判为疑似接续
        UnifiedElement a = multiRowTable(2, 700, true, null,
                List.of(List.of("甲", "10"), List.of("乙", "20")));
        UnifiedElement b = multiRowTable(3, 20, false, null,
                List.of(List.of("丙", "30"), List.of("丁", "40")));

        ContinuationOutcome outcome = resolver.joinContinuations(new ArrayList<>(List.of(a, b)), context());

        assertEquals(1, outcome.getElements().size());
        assertEquals(1, outcome.getContinuationCount());
        assertEquals(1, outcome.getSuspectedCount());
        assertTrue(outcome.getRelations().stream()
                .anyMatch(r -> "CONTINUATION_OF".equals(r.getType()) && r.getDetail().contains("疑似续表")));
    }

    @Test
    void unreadableHeadersWithDifferentWidthsShouldNotMerge() {
        UnifiedElement a = multiRowTable(2, 700, true, null,
                List.of(List.of("甲", "10"), List.of("乙", "20")));
        UnifiedElement b = multiRowTable(3, 20, false, null, List.of(List.of("丙", "30")));
        setColumnWidth(b, 300);

        ContinuationOutcome outcome = resolver.joinContinuations(new ArrayList<>(List.of(a, b)), context());

        assertEquals(2, outcome.getElements().size());
        assertEquals(0, outcome.getContinuationCount());
    }

    @Test
    void oneReadableHeaderShouldFallToRelaxedRule() {
        UnifiedElement a = multiRowTable(2, 700, true, 0,
                List.of(List.of("评分项", "分值"), List.of("甲", "10")));
        UnifiedElement b = multiRowTable(3, 20, false, null, List.of(List.of("丙", "30")));

        ContinuationOutcome outcome = resolver.joinContinuations(new ArrayList<>(List.of(a, b)), context());

        assertEquals(1, outcome.getElements().size());
        assertEquals(1, outcome.getSuspectedCount());
    }

    @Test
    void missingColumnCountShouldNotPassAsEqual() {
        // 两侧列表都缺失：不再当作"列数相同"走主规则，改按参考行列宽判为疑似接续
        UnifiedElement a = multiRowTable(2, 700, true, 0,
                List.of(List.of("评分项", "分值"), List.of("甲", "10")));
        a.setCols(null);
        UnifiedElement b = multiRowTable(3, 20, false, 0,
                List.of(List.of("评分项", "分值"), List.of("乙", "20")));
        b.setCols(null);

        ContinuationOutcome outcome = resolver.joinContinuations(new ArrayList<>(List.of(a, b)), context());

        assertEquals(1, outcome.getElements().size());
        assertEquals(1, outcome.getSuspectedCount());
    }

    @Test
    void knownDifferentColumnCountShouldNotMerge() {
        UnifiedElement a = multiRowTable(2, 700, true, 0,
                List.of(List.of("评分项", "分值"), List.of("甲", "10")));
        UnifiedElement b = multiRowTable(3, 20, false, 0,
                List.of(List.of("评分项", "分值", "备注"), List.of("乙", "20", "-")));

        ContinuationOutcome outcome = resolver.joinContinuations(new ArrayList<>(List.of(a, b)), context());

        assertEquals(2, outcome.getElements().size());
        assertEquals(0, outcome.getContinuationCount());
    }
}
