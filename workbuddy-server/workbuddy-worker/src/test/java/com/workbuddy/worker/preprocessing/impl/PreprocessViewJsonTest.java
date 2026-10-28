package com.workbuddy.worker.preprocessing.impl;
import com.workbuddy.common.enums.preprocess.PreprocessFieldType;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;

import com.workbuddy.common.domain.parse.Provenance;
import com.workbuddy.common.domain.preprocess.NormalizedField;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 预处理派生视图 JSON 往返单测（schema 只增不删的序列化基线）。
 */
class PreprocessViewJsonTest {

    /**
     * 守护 {@code viewShouldRoundTrip}：该场景行为改动即失败。
     */
    @Test
    void viewShouldRoundTrip() {
        PreprocessView view = new PreprocessView();
        view.setViewId("pv-doc-1-preproc-default-v1");
        view.setDocumentId("doc-1");
        view.setFileResultId(10L);
        view.setSourceFileRef("F-1");
        view.setUpstreamProductRef(50L);
        view.setStrategyVersion("preproc-default-v1");
        view.setOptions(Map.of("pageHeaderFooter", "MARK", "fieldNormalization", "ON"));

        ViewElement element = new ViewElement();
        element.setElementId("n-1");
        element.setType("PARAGRAPH");
        element.setStatus(ViewElementStatus.NORMAL.name());
        element.setRawText("投标保证金为人民币叁佰万元整（￥3,000,000.00）。");
        element.setDisplayText("投标保证金为人民币叁佰万元整（￥3,000,000.00）。");
        element.setNormalizedText("投标保证金为人民币叁佰万元整（￥3000000.00）。");
        element.setNormalizedFields(List.of(NormalizedField.of(
                PreprocessFieldType.AMOUNT.name(), "3000000.00", "元", "amount-cn-v1")));
        element.setPreprocessTrace(List.of(TraceEntry.of("thousand-sep-v1", PreprocessFieldType.AMOUNT.name(),
                TraceEntry.ACTION_REPLACE, "3,000,000.00", "3000000.00", "千分位逗号去除")));
        element.setProvenance(new Provenance("F-1", "pdf#page(1)"));

        ViewCell cell = new ViewCell();
        cell.setCellId("tc-1");
        cell.setText("30");
        cell.setRow(1);
        cell.setCol(2);
        cell.setIsHeader(true);
        cell.setNormalizedText("30");
        cell.setTrace(List.of());
        element.setCells(List.of(cell));
        view.setElements(List.of(element));

        String json = JsonUtil.toJsonStr(view);
        PreprocessView restored = JsonUtil.toObject(json, PreprocessView.class);

        assertNotNull(restored);
        assertEquals("pv-doc-1-preproc-default-v1", restored.getViewId());
        assertEquals("doc-1", restored.getDocumentId());
        assertEquals(10L, restored.getFileResultId());
        assertEquals(50L, restored.getUpstreamProductRef());
        assertEquals("MARK", restored.getOptions().get("pageHeaderFooter"));
        assertEquals(1, restored.getElements().size());
        ViewElement restoredElement = restored.getElements().getFirst();
        assertEquals(ViewElementStatus.NORMAL.name(), restoredElement.getStatus());
        assertEquals(1, restoredElement.getNormalizedFields().size());
        assertEquals("3000000.00", restoredElement.getNormalizedFields().getFirst().getValue());
        assertEquals(1, restoredElement.getPreprocessTrace().size());
        assertEquals("tc-1", restoredElement.getCells().getFirst().getCellId());
        assertEquals(1, restoredElement.getCells().getFirst().getRow());
        assertEquals(2, restoredElement.getCells().getFirst().getCol());
        assertEquals(Boolean.TRUE, restoredElement.getCells().getFirst().getIsHeader());
    }
}
