package com.workbuddy.worker.chunking.impl.table;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.workbuddy.worker.testkit.PropertiesFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 整表切片器单测：整表一片 / 超 maxLen 降级行级。
 */
class TableWholeStrategyTest {

    private final TableWholeStrategy strategy = new TableWholeStrategy();

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

    private ViewCell viewCell(String id, String text) {
        ViewCell cell = new ViewCell();
        cell.setCellId(id);
        cell.setText(text);
        cell.setNormalizedText(text);
        return cell;
    }

    private SliceContext context(UnifiedElement table, String configJson) {
        SliceContext context = new SliceContext();
        context.setView(new PreprocessView());
        context.setStrategy(new ChunkStrategyParser(PropertiesFixtures.chunk()).parse(configJson));
        context.setTitlePath(List.of("第一章 投标人须知"));
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

    /** 表头两列 + 3 行数据（每行两列各 text） */
    private UnifiedElement tableWith(String cellText) {
        UnifiedElement table = new UnifiedElement();
        table.setId("t-1");
        table.setType(UnifiedElementType.TABLE.name());
        table.setPage(2);
        List<UnifiedElement> cells = new ArrayList<>();
        cells.add(cell("h1", 0, 0, true, "评分项"));
        cells.add(cell("h2", 0, 1, true, "分值"));
        for (int r = 1; r <= 3; r++) {
            cells.add(cell("c" + r + "-1", r, 0, false, cellText + r));
            cells.add(cell("c" + r + "-2", r, 1, false, "30"));
        }
        table.setCells(cells);
        return table;
    }

    /**
     * 守护 {@code wholeTableShouldProduceSingleChunk}：该场景行为改动即失败。
     */
    @Test
    void wholeTableShouldProduceSingleChunk() {
        UnifiedElement table = tableWith("行");
        List<ViewCell> viewCells = new ArrayList<>();
        for (UnifiedElement cell : table.getCells()) {
            viewCells.add(viewCell(cell.getId(), cell.getText()));
        }
        List<Chunk> chunks = strategy.slice(tableElement(viewCells),
                context(table, "{\"routes\":{\"table\":{\"algorithm\":\"whole-table\"}}}"));

        assertEquals(1, chunks.size());
        Chunk chunk = chunks.getFirst();
        assertEquals(ChunkContentType.TABLE.name(), chunk.getContentType());
        assertTrue(chunk.getContent().startsWith("| 评分项 | 分值 |\n|---|---|"));
        assertTrue(chunk.getContent().contains("行1") && chunk.getContent().contains("行3"));
        // 整表一片：分隔行只出现一次
        assertEquals(1, chunk.getContent().split("\\|---\\|", -1).length - 1);
        assertEquals("t-1", chunk.getTableRef());
    }

    /**
     * 守护 {@code overlongTableShouldDegradeToRowSlice}：该场景行为改动即失败。
     */
    @Test
    void overlongTableShouldDegradeToRowSlice() {
        // 每行 60 字符（长行），整表远超 maxLen=50 → 降级行级：每行一片
        UnifiedElement table = tableWith("长".repeat(60));
        List<ViewCell> viewCells = new ArrayList<>();
        for (UnifiedElement cell : table.getCells()) {
            viewCells.add(viewCell(cell.getId(), cell.getText()));
        }
        List<Chunk> chunks = strategy.slice(tableElement(viewCells),
                context(table, "{\"routes\":{\"table\":{\"algorithm\":\"whole-table\","
                        + "\"params\":{\"maxLen\":\"50\"}}}}"));

        assertEquals(3, chunks.size());
        assertTrue(chunks.stream().allMatch(c -> ChunkContentType.TABLE.name().equals(c.getContentType())));
        assertTrue(chunks.stream().allMatch(c -> c.getContent().contains("| 评分项 | 分值 |")));
    }
}
