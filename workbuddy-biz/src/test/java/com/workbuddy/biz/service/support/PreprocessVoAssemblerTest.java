package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.preprocess.NormalizedField;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import com.workbuddy.biz.service.support.assemble.PreprocessVoAssembler;
import com.workbuddy.biz.service.support.stats.PreprocessStatsSupport;
import com.workbuddy.biz.service.support.assemble.PreprocessVoAssemblerImpl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 预处理详情 VO 组装器单测（纯映射无状态类，用真实实例断言）。
 */
class PreprocessVoAssemblerTest {

    private final PreprocessVoAssembler assembler = new PreprocessVoAssemblerImpl();

    private ViewElement element(String id, String status, String display, String normalized) {
        ViewElement element = new ViewElement();
        element.setElementId(id);
        element.setStatus(status);
        element.setDisplayText(display);
        element.setNormalizedText(normalized);
        element.setRawText("原文" + id);
        return element;
    }

    /**
     * 视图摘要：改写、剔除、重复份与字段计数。
     *
     * <p>守护 {@code toSummary}：分类口径错即失败。
     */
    @Test
    void toSummaryShouldCountChangedExcludedRepeatedAndFields() {
        PreprocessView view = new PreprocessView();
        view.getElements().add(element("e-1", ViewElementStatus.NORMAL.name(), "展示", "检索"));
        view.getElements().add(element("e-2", ViewElementStatus.NORMAL.name(), "展示2", "展示2"));
        view.getElements().add(element("e-3", ViewElementStatus.EXCLUDED_HEADER.name(), "展示3", "检索3"));
        view.getElements().add(element("e-4", ViewElementStatus.REPEATED.name(), "展示4", null));

        var summary = PreprocessStatsSupport.summaryOf(view);

        assertEquals(4, summary.getElementCount());
        assertEquals(2, summary.getChangedCount());
        assertEquals(1, summary.getExcludedCount());
        assertEquals(1, summary.getRepeatedCount());
        assertEquals(0, summary.getFieldCount());
    }

    /**
     * 视图摘要：标记档的重复份计入仅标记，不进剔除。
     *
     * <p>守护 {@code toSummary}：分类口径错即失败。
     */
    @Test
    void toSummaryShouldCountMarkedRepeatInMarkedOnly() {
        PreprocessView view = new PreprocessView();
        view.getElements().add(element("e-1", ViewElementStatus.NORMAL.name(), "展示", "检索"));
        view.getElements().add(element("e-2", ViewElementStatus.MARKED_REPEAT.name(), "展示2", "检索2"));
        view.getElements().add(element("e-3", ViewElementStatus.REPEATED.name(), "展示3", null));
        view.getElements().add(element("e-4", ViewElementStatus.EXCLUDED_NOISE.name(), "展示4", null));
        view.getElements().add(element("e-5", ViewElementStatus.NOISE.name(), "展示5", "检索5"));

        var summary = PreprocessStatsSupport.summaryOf(view);

        // 标记档的重复份只计「仅标记」，不进「重复」与「不进切片」口径
        assertEquals(5, summary.getElementCount());
        assertEquals(2, summary.getMarkedCount());
        assertEquals(1, summary.getRepeatedCount());
        assertEquals(1, summary.getExcludedCount());
        assertEquals(2, summary.getChunkSkippedCount());
        assertEquals(Integer.valueOf(1), summary.getStatusCounts().get(ViewElementStatus.MARKED_REPEAT.name()));
    }

    /**
     * 元素 VO：可选字段逐列映射，轨迹只透传非 KEEP 条目。
     *
     * <p>守护 {@code toElementVOs}：KEEP 条目被下发或字段漏搬即失败。
     */
    @Test
    void toElementVOsShouldFilterKeepTracesAndMapCells() {
        PreprocessView view = new PreprocessView();
        ViewElement element = element("t-1", ViewElementStatus.NORMAL.name(), "展示", "检索");
        element.setType("PARAGRAPH");
        element.setPage(7);
        List<TraceEntry> traces = new ArrayList<>();
        traces.add(TraceEntry.of("r-1", null, TraceEntry.ACTION_KEEP, null, null, "无需改写"));
        traces.add(TraceEntry.of("r-2", "金额", TraceEntry.ACTION_REPLACE, "前", "后", "改写"));
        element.setPreprocessTrace(traces);
        List<NormalizedField> fields = new ArrayList<>();
        NormalizedField field = new NormalizedField();
        field.setField("金额");
        field.setValue("30000");
        field.setUnit("元");
        field.setRule("amount-normalize");
        fields.add(field);
        element.setNormalizedFields(fields);
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
        var vo = vos.getFirst();
        assertEquals("t-1", vo.getElementId());
        assertEquals("PARAGRAPH", vo.getType());
        assertEquals(ViewElementStatus.NORMAL.name(), vo.getStatus());
        assertEquals(7, vo.getPage());
        assertEquals("原文t-1", vo.getRawText());
        assertEquals("展示", vo.getDisplayText());
        assertEquals("检索", vo.getNormalizedText());

        assertEquals(1, vo.getFields().size());
        assertEquals("金额", vo.getFields().getFirst().getField());
        assertEquals("30000", vo.getFields().getFirst().getValue());
        assertEquals("元", vo.getFields().getFirst().getUnit());
        assertEquals("amount-normalize", vo.getFields().getFirst().getRule());

        assertEquals(1, vo.getTrace().size());
        var trace = vo.getTrace().getFirst();
        assertEquals(TraceEntry.ACTION_REPLACE, trace.getAction());
        assertEquals("金额", trace.getField());
        assertEquals("前", trace.getBefore());
        assertEquals("后", trace.getAfter());
        assertEquals("改写", trace.getEvidence());

        assertEquals(1, vo.getCells().size());
        var cellVo = vo.getCells().getFirst();
        assertEquals("c-1", cellVo.getCellId());
        assertEquals("单元格", cellVo.getText());
        assertEquals(0, cellVo.getRow());
        assertEquals(1, cellVo.getCol());
        assertEquals(true, cellVo.getIsHeader());
        assertEquals("单元格", cellVo.getNormalizedText());
    }
}
