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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表+引导段落切片器单测：前导段前缀（含截断）/ 无前导段退化行级。
 */
class TableContextMergedStrategyTest {

    private final TableContextMergedStrategy strategy = new TableContextMergedStrategy();

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

    private SliceContext context(UnifiedElement table, String configJson, String leadParagraph) {
        SliceContext context = new SliceContext();
        context.setView(new PreprocessView());
        context.setStrategy(new ChunkStrategyParser(new ChunkProperties()).parse(configJson));
        context.setTitlePath(List.of("第一章 投标人须知"));
        context.setLeadParagraph(leadParagraph);
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

    /** 表头两列 + 2 行数据 */
    private UnifiedElement tableWithLongRows() {
        UnifiedElement table = new UnifiedElement();
        table.setId("t-1");
        table.setType(UnifiedElementType.TABLE.name());
        table.setPage(2);
        List<UnifiedElement> cells = new ArrayList<>();
        cells.add(cell("h1", 0, 0, true, "评分项"));
        cells.add(cell("h2", 0, 1, true, "分值"));
        cells.add(cell("c1-1", 1, 0, false, "方案完整性可行性与先进性由评审组综合打分评估"));
        cells.add(cell("c1-2", 1, 1, false, "30"));
        cells.add(cell("c2-1", 2, 0, false, "履约能力与售后服务承诺综合评定占分较高"));
        cells.add(cell("c2-2", 2, 1, false, "20"));
        table.setCells(cells);
        return table;
    }

    @Test
    void leadParagraphShouldPrefixEveryChunk() {
        UnifiedElement table = tableWithLongRows();
        List<ViewCell> viewCells = new ArrayList<>();
        for (UnifiedElement cell : table.getCells()) {
            viewCells.add(viewCell(cell.getId(), cell.getText()));
        }
        List<Chunk> chunks = strategy.slice(tableElement(viewCells),
                context(table, "{\"routes\":{\"table\":{\"algorithm\":\"context-merged\",\"params\":{\"leadMaxLen\":\"50\",\"groupThreshold\":\"5\"}}}}",
                        "引出本表的说明段落"));

        assertEquals(2, chunks.size());
        assertTrue(chunks.stream().allMatch(c -> c.getContent().startsWith("引出本表的说明段落\n")));
        assertTrue(chunks.stream().allMatch(c -> ChunkContentType.TABLE.name().equals(c.getContentType())));
        assertTrue(chunks.getFirst().getContent().contains("| 评分项 | 分值 |"));
    }

    @Test
    void longLeadShouldTruncate() {
        UnifiedElement table = tableWithLongRows();
        List<ViewCell> viewCells = new ArrayList<>();
        for (UnifiedElement cell : table.getCells()) {
            viewCells.add(viewCell(cell.getId(), cell.getText()));
        }
        String longLead = "引".repeat(100);
        List<Chunk> chunks = strategy.slice(tableElement(viewCells),
                context(table, "{\"routes\":{\"table\":{\"algorithm\":\"context-merged\",\"params\":{\"leadMaxLen\":\"10\",\"groupThreshold\":\"5\"}}}}",
                        longLead));

        assertEquals(2, chunks.size());
        assertTrue(chunks.stream().allMatch(c ->
                c.getContent().startsWith("引".repeat(10) + "…\n")));
    }

    @Test
    void noLeadShouldBehaveAsRowSlice() {
        UnifiedElement table = tableWithLongRows();
        List<ViewCell> viewCells = new ArrayList<>();
        for (UnifiedElement cell : table.getCells()) {
            viewCells.add(viewCell(cell.getId(), cell.getText()));
        }
        List<Chunk> chunks = strategy.slice(tableElement(viewCells),
                context(table, "{\"routes\":{\"table\":{\"algorithm\":\"context-merged\",\"params\":{\"groupThreshold\":\"5\"}}}}", null));

        assertEquals(2, chunks.size());
        assertTrue(chunks.stream().allMatch(c -> c.getContent().startsWith("| 评分项 | 分值 |")));
    }
}
