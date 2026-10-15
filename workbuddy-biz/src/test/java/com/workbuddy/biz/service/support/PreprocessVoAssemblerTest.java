package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 预处理详情 VO 组装器单测（纯映射无状态类，用真实实例断言）。
 *
 * @author cxxl
 */
class PreprocessVoAssemblerTest {

    private final PreprocessVoAssembler assembler = new PreprocessVoAssembler();

    private ViewElement element(String id, String status, String display, String normalized) {
        ViewElement element = new ViewElement();
        element.setElementId(id);
        element.setStatus(status);
        element.setDisplayText(display);
        element.setNormalizedText(normalized);
        element.setRawText("原文" + id);
        return element;
    }

    @Test
    void toSummaryShouldCountChangedExcludedRepeatedAndFields() {
        PreprocessView view = new PreprocessView();
        view.getElements().add(element("e-1", ViewElementStatus.NORMAL.name(), "展示", "检索"));
        view.getElements().add(element("e-2", ViewElementStatus.NORMAL.name(), "展示2", "展示2"));
        view.getElements().add(element("e-3", ViewElementStatus.EXCLUDED_HEADER.name(), "展示3", "检索3"));
        view.getElements().add(element("e-4", ViewElementStatus.REPEATED.name(), "展示4", null));

        var summary = assembler.toSummary(view);

        assertEquals(4, summary.getElementCount());
        assertEquals(2, summary.getChangedCount());
        assertEquals(1, summary.getExcludedCount());
        assertEquals(1, summary.getRepeatedCount());
        assertEquals(0, summary.getFieldCount());
    }

    @Test
    void toSummaryShouldCountMarkedRepeatInMarkedOnly() {
        PreprocessView view = new PreprocessView();
        view.getElements().add(element("e-1", ViewElementStatus.NORMAL.name(), "展示", "检索"));
        view.getElements().add(element("e-2", ViewElementStatus.MARKED_REPEAT.name(), "展示2", "检索2"));
        view.getElements().add(element("e-3", ViewElementStatus.REPEATED.name(), "展示3", null));
        view.getElements().add(element("e-4", ViewElementStatus.EXCLUDED_NOISE.name(), "展示4", null));
        view.getElements().add(element("e-5", ViewElementStatus.NOISE.name(), "展示5", "检索5"));

        var summary = assembler.toSummary(view);

        // 标记档的重复份只计「仅标记」，不进「重复」与「不进切片」口径
        assertEquals(5, summary.getElementCount());
        assertEquals(2, summary.getMarkedCount());
        assertEquals(1, summary.getRepeatedCount());
        assertEquals(1, summary.getExcludedCount());
        assertEquals(2, summary.getChunkSkippedCount());
        assertEquals(Integer.valueOf(1), summary.getStatusCounts().get(ViewElementStatus.MARKED_REPEAT.name()));
    }

    @Test
    void toElementVOsShouldFilterKeepTracesAndMapCells() {
        PreprocessView view = new PreprocessView();
        ViewElement element = element("t-1", ViewElementStatus.NORMAL.name(), "展示", "检索");
        List<TraceEntry> traces = new ArrayList<>();
        traces.add(TraceEntry.of("r-1", null, TraceEntry.ACTION_KEEP, null, null, "无需改写"));
        traces.add(TraceEntry.of("r-2", null, TraceEntry.ACTION_REPLACE, "前", "后", "改写"));
        element.setPreprocessTrace(traces);
        List<ViewCell> cells = new ArrayList<>();
        ViewCell cell = new ViewCell();
        cell.setCellId("c-1");
        cell.setText("单元格");
        cell.setRow(0);
        cell.setCol(1);
        cell.setIsHeader(true);
        cell.setNormalizedText("单元格");
        cells.add(cell);
        element.setCells(cells);
        view.getElements().add(element);

        var vos = assembler.toElementVOs(view);

        assertEquals(1, vos.size());
        assertEquals("t-1", vos.getFirst().getElementId());
        assertEquals(1, vos.getFirst().getTrace().size());
        assertEquals(TraceEntry.ACTION_REPLACE, vos.getFirst().getTrace().getFirst().getAction());
        assertEquals(1, vos.getFirst().getCells().size());
        assertEquals(true, vos.getFirst().getCells().getFirst().getIsHeader());
    }
}
