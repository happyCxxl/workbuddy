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
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.workbuddy.worker.testkit.PropertiesFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行组切片器单测：行数上限结算 / 长度上限结算 / 表头随片。
 */
class TableRowGroupStrategyTest {

    private final TableRowGroupStrategy strategy = new TableRowGroupStrategy();

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

    /** 表头两列 + 5 行数据（每行两列各 text） */
    private UnifiedElement tableWith(String cellText) {
        UnifiedElement table = new UnifiedElement();
        table.setId("t-1");
        table.setType(UnifiedElementType.TABLE.name());
        table.setPage(2);
        List<UnifiedElement> cells = new ArrayList<>();
        cells.add(cell("h1", 0, 0, true, "评分项"));
        cells.add(cell("h2", 0, 1, true, "分值"));
        for (int r = 1; r <= 5; r++) {
            cells.add(cell("c" + r + "-1", r, 0, false, cellText + r));
            cells.add(cell("c" + r + "-2", r, 1, false, "30"));
        }
        table.setCells(cells);
        return table;
    }

    /**
     * 守护 {@code groupSizeShouldSettleChunkPerGroup}：该场景行为改动即失败。
     */
    @Test
    void groupSizeShouldSettleChunkPerGroup() {
        UnifiedElement table = tableWith("行");
        List<ViewCell> viewCells = new ArrayList<>();
        for (UnifiedElement cell : table.getCells()) {
            viewCells.add(viewCell(cell.getId(), cell.getText()));
        }
        List<Chunk> chunks = strategy.slice(tableElement(viewCells),
                context(table, "{\"routes\":{\"table\":{\"algorithm\":\"row-group\","
                        + "\"params\":{\"groupSize\":\"2\",\"maxLen\":\"1000\"}}}}"));

        assertEquals(3, chunks.size());
        assertTrue(chunks.stream().allMatch(c -> ChunkContentType.TABLE.name().equals(c.getContentType())));
        // 每片带表头（拆片重复表头）
        assertTrue(chunks.stream().allMatch(c -> c.getContent().contains("| 评分项 | 分值 |")
                && c.getContent().contains("|---|---|")));
        assertTrue(chunks.getFirst().getContent().contains("行1") && chunks.getFirst().getContent().contains("行2"));
        assertFalse(chunks.getFirst().getContent().contains("行3"));
        assertEquals("t-1", chunks.getFirst().getTableRef());
    }

    /**
     * 守护 {@code maxLenShouldSettleChunkBeforeOverflow}：该场景行为改动即失败。
     */
    @Test
    void maxLenShouldSettleChunkBeforeOverflow() {
        UnifiedElement table = tableWith("内容较长的一行");
        List<ViewCell> viewCells = new ArrayList<>();
        for (UnifiedElement cell : table.getCells()) {
            viewCells.add(viewCell(cell.getId(), cell.getText()));
        }
        // 每行约 10 字符，maxLen=25：两行一组（20 ≤ 25），第三行会使组超限 → 结算 → 3 组（2/2/1）
        List<Chunk> chunks = strategy.slice(tableElement(viewCells),
                context(table, "{\"routes\":{\"table\":{\"algorithm\":\"row-group\","
                        + "\"params\":{\"groupSize\":\"10\",\"maxLen\":\"25\"}}}}"));

        assertEquals(3, chunks.size());
        assertTrue(chunks.getFirst().getContent().contains("内容较长的一行1"));
        assertTrue(chunks.getFirst().getContent().contains("内容较长的一行2"));
        assertFalse(chunks.getFirst().getContent().contains("内容较长的一行3"));
    }
}
