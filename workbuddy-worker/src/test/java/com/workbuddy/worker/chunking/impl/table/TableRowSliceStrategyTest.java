package com.workbuddy.worker.chunking.impl.table;

import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.testkit.StrategyFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表格切片器单测：Markdown 表格形态（表头行 + 分隔行 + 数据行、拆片重复表头）、行组（<30 字符每 3 行）、
 * 评分项不分离、无表头降级（仅数据行）、空表头视为无表头、sourceElementIds/pageRange/tableRef。
 */
class TableRowSliceStrategyTest {

    private final TableRowSliceStrategy strategy = new TableRowSliceStrategy();

    private UnifiedElement cell(String id, int row, int col, boolean header, String text) {
        UnifiedElement cell = new UnifiedElement();
        cell.setId(id);
        cell.setType(UnifiedElementType.TABLE_CELL.name());
        cell.setRow(row);
        cell.setCol(col);
        cell.setIsHeader(header);
        cell.setText(text);
        return cell;
    }

    private SliceContext context(UnifiedElement table) {
        SliceContext context = new SliceContext();
        context.setView(new PreprocessView());
        ChunkProperties props = new ChunkProperties();
        context.setStrategy(StrategyFixtures.chunkDefaults(props));
        context.setTitlePath(List.of("第一章 投标人须知", "1.3 评分标准"));
        Map<String, UnifiedElement> byId = new HashMap<>();
        byId.put("t-1", table);
        context.setById(byId);
        return context;
    }

    private ViewElement tableElement(List<ViewCell> viewCells) {
        ViewElement element = new ViewElement();
        element.setElementId("t-1");
        element.setType(UnifiedElementType.TABLE.name());
        element.setPage(2);
        element.setStatus(ViewElementStatus.NORMAL.name());
        element.setCells(viewCells);
        return element;
    }

    private ViewCell viewCell(String id, String text) {
        ViewCell cell = new ViewCell();
        cell.setCellId(id);
        cell.setText(text);
        cell.setNormalizedText(text);
        return cell;
    }

    /**
     * 守护 {@code headerWithRowAndGroupingShouldKeepScoringItemTogether}：该场景行为改动即失败。
     */
    @Test
    void headerWithRowAndGroupingShouldKeepScoringItemTogether() {
        UnifiedElement table = new UnifiedElement();
        table.setId("t-1");
        table.setType(UnifiedElementType.TABLE.name());
        table.setPage(2);
        List<UnifiedElement> cells = new ArrayList<>();
        cells.add(cell("h1", 0, 0, true, "评分项"));
        cells.add(cell("h2", 0, 1, true, "分值"));
        // 4 行数据（行文本 < 30 字符 → 每 3 行一组）
        cells.add(cell("c1", 1, 0, false, "A1 报价"));
        cells.add(cell("c2", 1, 1, false, "30"));
        cells.add(cell("c3", 2, 0, false, "A2 技术方案"));
        cells.add(cell("c4", 2, 1, false, "40"));
        cells.add(cell("c5", 3, 0, false, "A3 履约能力"));
        cells.add(cell("c6", 3, 1, false, "15"));
        cells.add(cell("c7", 4, 0, false, "B1 售后承诺"));
        cells.add(cell("c8", 4, 1, false, "10"));
        table.setCells(cells);

        List<ViewCell> viewCells = List.of(
                viewCell("h1", "评分项"), viewCell("h2", "分值"),
                viewCell("c1", "A1 报价"), viewCell("c2", "30"),
                viewCell("c3", "A2 技术方案"), viewCell("c4", "40"),
                viewCell("c5", "A3 履约能力"), viewCell("c6", "15"),
                viewCell("c7", "B1 售后承诺"), viewCell("c8", "10"));

        List<Chunk> chunks = strategy.slice(tableElement(viewCells), context(table));

        assertEquals(2, chunks.size());
        Chunk first = chunks.getFirst();
        assertEquals(ChunkContentType.TABLE.name(), first.getContentType());
        // Markdown 表格：表头行 + 分隔行 + 数据行（拆片重复表头）
        assertTrue(first.getContent().contains("| 评分项 | 分值 |"));
        assertTrue(first.getContent().contains("|---|---|"));
        assertTrue(first.getContent().contains("| A1 报价 | 30 |"));
        assertTrue(first.getContent().contains("| A2 技术方案 | 40 |"));
        assertTrue(first.getContent().contains("| A3 履约能力 | 15 |"));
        // 评分项与分值同片不分离
        assertTrue(first.getContent().contains("A1 报价") && first.getContent().contains("30"));
        assertEquals("t-1", first.getTableRef());
        assertEquals(List.of(2), first.getPageRange());
        assertTrue(first.getSourceElementIds().contains("c1"));
    }

    /**
     * 守护 {@code longRowShouldProduceSingleChunkPerRow}：该场景行为改动即失败。
     */
    @Test
    void longRowShouldProduceSingleChunkPerRow() {
        UnifiedElement table = new UnifiedElement();
        table.setId("t-1");
        table.setType(UnifiedElementType.TABLE.name());
        String longText = "低于基准价 1% 以内得 30 分；每高于基准价 1% 扣 0.5 分，扣完为止。综合评分细则说明。";
        List<UnifiedElement> cells = new ArrayList<>();
        cells.add(cell("h1", 0, 0, true, "评分标准"));
        cells.add(cell("c1", 1, 0, false, longText));
        cells.add(cell("c2", 2, 0, false, "方案完整性、可行性与先进性由评审组综合打分。"));
        table.setCells(cells);

        List<ViewCell> viewCells = List.of(
                viewCell("h1", "评分标准"), viewCell("c1", longText),
                viewCell("c2", "方案完整性、可行性与先进性由评审组综合打分。"));

        List<Chunk> chunks = strategy.slice(tableElement(viewCells), context(table));

        assertEquals(2, chunks.size());
        assertTrue(chunks.getFirst().getContent().startsWith("| 评分标准 |"));
    }

    /**
     * 守护 {@code noHeaderShouldJoinCellTextsOnly}：该场景行为改动即失败。
     */
    @Test
    void noHeaderShouldJoinCellTextsOnly() {
        UnifiedElement table = new UnifiedElement();
        table.setId("t-1");
        table.setType(UnifiedElementType.TABLE.name());
        List<UnifiedElement> cells = new ArrayList<>();
        cells.add(cell("c1", 1, 0, false, "无表头值一"));
        cells.add(cell("c2", 1, 1, false, "无表头值二"));
        table.setCells(cells);

        List<ViewCell> viewCells = List.of(viewCell("c1", "无表头值一"), viewCell("c2", "无表头值二"));

        List<Chunk> chunks = strategy.slice(tableElement(viewCells), context(table));

        assertEquals(1, chunks.size());
        // 无表头：只输出数据行（无分隔行）
        assertEquals("| 无表头值一 | 无表头值二 |", chunks.getFirst().getContent());
    }

    /**
     * 守护 {@code blankHeaderCellsShouldBeTreatedAsNoHeader}：该场景行为改动即失败。
     */
    @Test
    void blankHeaderCellsShouldBeTreatedAsNoHeader() {
        UnifiedElement table = new UnifiedElement();
        table.setId("t-1");
        table.setType(UnifiedElementType.TABLE.name());
        List<UnifiedElement> cells = new ArrayList<>();
        // 表头单元格存在但文本为空 → 不算表头，不产出空表头行+分隔行
        cells.add(cell("h1", 0, 0, true, ""));
        cells.add(cell("h2", 0, 1, true, ""));
        cells.add(cell("c1", 1, 0, false, "停电计划核对"));
        cells.add(cell("c2", 1, 1, false, "停电警示悬挂"));
        table.setCells(cells);

        List<ViewCell> viewCells = List.of(viewCell("c1", "停电计划核对"), viewCell("c2", "停电警示悬挂"));

        List<Chunk> chunks = strategy.slice(tableElement(viewCells), context(table));

        assertEquals(1, chunks.size());
        assertEquals("| 停电计划核对 | 停电警示悬挂 |", chunks.getFirst().getContent());
    }
}
