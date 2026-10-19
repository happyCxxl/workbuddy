package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.preprocess.NormalizedField;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.preprocess.ViewElementStatus;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.filecenter.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预处理统计与结论单测。
 *
 * <p>守护 {@code stats} 的口径（保留/剔除/仅标记、字段与改写、规则命中、受影响页范围）与
 * {@code summary} 的陈述规则：状态归类错或页范围算错，执行树就会把剔除了的内容说成「全部进入切片」。
 */
class PreprocessStatsSupportTest {

    /**
     * 产物缺失时不陈述统计。
     *
     * <p>守护 {@code stats} 的空产物兜底：返回空表会让详情页显示 0 元素而不是「不可读」。
     */
    @Test
    void statsShouldReturnNullWhenViewMissing() {
        assertNull(PreprocessStatsSupport.stats(null, null, null));
    }

    /**
     * 全量口径：状态分类、保留占比、字段/改写/规则命中、状态与字段分布、受影响页范围。
     *
     * <p>守护 {@code stats} 的逐项口径：任一类计数或页范围写错即失败。
     */
    @Test
    void statsShouldAggregateAllDimensions() {
        LocalDateTime started = LocalDateTime.of(2024, 7, 1, 8, 0, 0);

        Map<String, Object> stats = PreprocessStatsSupport.stats(started, started.plusSeconds(2), view(
                element(ViewElementStatus.NORMAL, "同", "同", 1),
                element(ViewElementStatus.EXCLUDED_HEADER, "页眉", "页眉", 2),
                element(ViewElementStatus.EXCLUDED_TOC, "目录", "目录", 3),
                element(ViewElementStatus.REPEATED, "重复", "重复", 4),
                element(ViewElementStatus.MARKED_HEADER, "页眉", "页眉", 5),
                element(ViewElementStatus.MARKED_REPEAT, "重复", "重复", 6),
                element(ViewElementStatus.NORMAL, "改后", "改前", 7),
                elementWithFields(8),
                elementWithTrace(9, "encoding-utf8"),
                elementWithTrace(10, "text-tidy-space"),
                element(ViewElementStatus.NORMAL, null, null, null),
                blankStatusElement()));

        assertEquals(12, stats.get(PreprocessStatsSupport.KEY_ELEMENT_COUNT));
        // 剔除 2（页眉/目录）+ 重复份 1 → 不进切片 3，保留 9，占比 75%
        assertEquals(2, stats.get(PreprocessStatsSupport.KEY_EXCLUDED_COUNT));
        assertEquals(1, stats.get(PreprocessStatsSupport.KEY_REPEATED_COUNT));
        assertEquals(3, stats.get(PreprocessStatsSupport.KEY_CHUNK_SKIPPED_COUNT));
        assertEquals(9, stats.get(PreprocessStatsSupport.KEY_RETAINED_COUNT));
        assertEquals(75, stats.get(PreprocessStatsSupport.KEY_RETENTION_PERCENT));
        assertEquals(2, stats.get(PreprocessStatsSupport.KEY_MARKED_COUNT));
        assertEquals(3, stats.get(PreprocessStatsSupport.KEY_FIELD_COUNT));
        assertEquals(1, stats.get(PreprocessStatsSupport.KEY_CHANGED_COUNT));
        assertEquals(1, stats.get(PreprocessStatsSupport.KEY_ENCODING_COUNT));
        assertEquals(1, stats.get(PreprocessStatsSupport.KEY_TIDY_COUNT));
        assertEquals(2000L, stats.get(PreprocessStatsSupport.KEY_DURATION_MS));

        @SuppressWarnings("unchecked")
        Map<String, Integer> statusCounts = (Map<String, Integer>) stats.get(PreprocessStatsSupport.KEY_STATUS_COUNTS);
        assertEquals(6, statusCounts.get(ViewElementStatus.NORMAL.name()));
        assertEquals(1, statusCounts.get(ViewElementStatus.EXCLUDED_HEADER.name()));
        assertEquals(1, statusCounts.get(ViewElementStatus.REPEATED.name()));
        assertEquals(1, statusCounts.get("UNKNOWN"));

        @SuppressWarnings("unchecked")
        Map<String, Integer> fieldTypeCounts =
                (Map<String, Integer>) stats.get(PreprocessStatsSupport.KEY_FIELD_TYPE_COUNTS);
        assertEquals(2, fieldTypeCounts.get("金额"));
        assertEquals(1, fieldTypeCounts.get("日期"));

        // 受影响元素（非 NORMAL / 文本改写 / 提过字段）所在页 2..8；NORMAL 且无改动的页 1 不计
        assertEquals(2, stats.get(PreprocessStatsSupport.KEY_PAGE_FROM));
        assertEquals(8, stats.get(PreprocessStatsSupport.KEY_PAGE_TO));
    }

    /**
     * 没有任何受影响元素时不下发页范围。
     *
     * <p>守护 {@code putAffectedRange} 的兜底：全 NORMAL 也写 MAX/MIN 页码即失败。
     */
    @Test
    void statsShouldOmitPageRangeWhenNothingAffected() {
        Map<String, Object> stats = PreprocessStatsSupport.stats(null, null, view(
                element(ViewElementStatus.NORMAL, "同", "同", 1),
                element(ViewElementStatus.NORMAL, null, null, 2)));

        assertFalse(stats.containsKey(PreprocessStatsSupport.KEY_PAGE_FROM));
        assertFalse(stats.containsKey(PreprocessStatsSupport.KEY_PAGE_TO));
    }

    /**
     * 只有一个受影响页时始末页都写同一个页码。
     *
     * <p>守护 {@code putAffectedRange} 的 {@code min <= max} 判定：写成严格小于会漏掉单页。
     */
    @Test
    void statsShouldKeepSingleAffectedPage() {
        Map<String, Object> stats = PreprocessStatsSupport.stats(null, null, view(
                element(ViewElementStatus.MARKED_FOOTER, "页脚", "页脚", 6)));

        assertEquals(6, stats.get(PreprocessStatsSupport.KEY_PAGE_FROM));
        assertEquals(6, stats.get(PreprocessStatsSupport.KEY_PAGE_TO));
    }

    /**
     * 规则命中只认轨迹里的规则名前缀，空轨迹与空规则名不计。
     *
     * <p>守护 {@code hitRule} 的空规则跳过与前缀判定：把无轨迹元素也算命中会虚报改写数。
     */
    @Test
    void statsShouldCountRuleHitsOnlyForMatchingTrace() {
        ViewElement withBlankRuleFirst = element(ViewElementStatus.NORMAL, null, null, 1);
        withBlankRuleFirst.setPreprocessTrace(new ArrayList<>(List.of(trace(""), trace("encoding-gbk"))));
        ViewElement otherRulesOnly = element(ViewElementStatus.NORMAL, null, null, 2);
        otherRulesOnly.setPreprocessTrace(new ArrayList<>(List.of(trace("dedup-page"), trace("table-merge"))));

        Map<String, Object> stats = PreprocessStatsSupport.stats(null, null,
                view(withBlankRuleFirst, otherRulesOnly));

        assertEquals(1, stats.get(PreprocessStatsSupport.KEY_ENCODING_COUNT));
        assertEquals(0, stats.get(PreprocessStatsSupport.KEY_TIDY_COUNT));
    }

    /**
     * 读产物：对象可读时解析出派生视图，位置为空时为 null。
     *
     * <p>守护 {@code readView} 的委托：恒返回 null 会让预处理详情始终显示「产物不可读」。
     */
    @Test
    void readViewShouldParseStoredArtifact() {
        FileStorage fileStorage = Mockito.mock(FileStorage.class);
        ObjectRef ref = new ObjectRef(1L, StorageType.MINIO, "bucket", "preprocess/view.json");
        String json = "{\"elements\":[{\"elementId\":\"e1\",\"status\":\"NORMAL\"}]}";
        Mockito.when(fileStorage.getObject(ref)).thenReturn(json.getBytes(StandardCharsets.UTF_8));

        PreprocessView view = PreprocessStatsSupport.readView(fileStorage, ref);

        assertNotNull(view);
        assertEquals(1, view.getElements().size());
        assertEquals(ViewElementStatus.NORMAL.name(), view.getElements().getFirst().getStatus());
        assertNull(PreprocessStatsSupport.readView(fileStorage, null));
    }

    /**
     * 结论陈述：空视图、仅标记/剔除/重复份三段陈述、无剔除与失败兜底。
     *
     * <p>守护 {@code viewSummaryBody} 的阈值（&gt;0）与逐项陈述：漏项或把 0 也说成异常即失败。
     */
    @Test
    void summaryShouldStateEachShape() {
        assertNull(PreprocessStatsSupport.summary(null, null, PipelineTaskStatus.SUCCESS.name()));

        assertEquals("无任何可处理的元素（空视图）", PreprocessStatsSupport.summary(null,
                Map.of(PreprocessStatsSupport.KEY_ELEMENT_COUNT, 0), PipelineTaskStatus.SUCCESS.name()));

        Map<String, Object> clean = Map.of(
                PreprocessStatsSupport.KEY_ELEMENT_COUNT, 5,
                PreprocessStatsSupport.KEY_MARKED_COUNT, 0,
                PreprocessStatsSupport.KEY_EXCLUDED_COUNT, 0,
                PreprocessStatsSupport.KEY_REPEATED_COUNT, 0);
        assertEquals("未剔除内容，全部进入切片",
                PreprocessStatsSupport.summary(null, clean, PipelineTaskStatus.PARTIAL_SUCCESS.name()));

        Map<String, Object> dirty = Map.of(
                PreprocessStatsSupport.KEY_ELEMENT_COUNT, 9,
                PreprocessStatsSupport.KEY_MARKED_COUNT, 2,
                PreprocessStatsSupport.KEY_EXCLUDED_COUNT, 3,
                PreprocessStatsSupport.KEY_REPEATED_COUNT, 1);
        String text = PreprocessStatsSupport.summary(null, dirty, PipelineTaskStatus.SUCCESS.name());
        assertTrue(text.contains("2 项仅标记、仍在检索内容流"), text);
        assertTrue(text.contains("3 项已剔除出检索内容流"), text);
        assertTrue(text.contains("重复份 1 已剔除"), text);

        assertEquals("编码清理失败", PreprocessStatsSupport.summary("编码清理失败", null,
                PipelineTaskStatus.FAILED.name()));
        assertEquals("预处理失败", PreprocessStatsSupport.summary(" ", null, PipelineTaskStatus.FAILED.name()));
        assertNull(PreprocessStatsSupport.summary(null, clean, PipelineTaskStatus.RUNNING.name()));
    }

    /** 视图夹具 */
    private PreprocessView view(ViewElement... elements) {
        PreprocessView view = new PreprocessView();
        view.setElements(new ArrayList<>(List.of(elements)));
        return view;
    }

    /** 元素夹具：状态 + 检索/展示文本 + 页码 */
    private ViewElement element(ViewElementStatus status, String normalizedText, String displayText, Integer page) {
        ViewElement element = new ViewElement();
        element.setStatus(status == null ? null : status.name());
        element.setNormalizedText(normalizedText);
        element.setDisplayText(displayText);
        element.setPage(page);
        return element;
    }

    /** 状态为空串的元素（分布归入 UNKNOWN） */
    private ViewElement blankStatusElement() {
        ViewElement element = element(ViewElementStatus.NORMAL, null, null, null);
        element.setStatus("");
        return element;
    }

    /** 带标准化字段的元素（金额两条 + 日期一条） */
    private ViewElement elementWithFields(int page) {
        ViewElement element = element(ViewElementStatus.NORMAL, null, null, page);
        List<NormalizedField> fields = new ArrayList<>();
        fields.add(field("金额"));
        fields.add(field("日期"));
        fields.add(field("金额"));
        element.setNormalizedFields(fields);
        return element;
    }

    /** 带处理轨迹的元素 */
    private ViewElement elementWithTrace(int page, String rule) {
        ViewElement element = element(ViewElementStatus.NORMAL, null, null, page);
        element.setPreprocessTrace(new ArrayList<>(List.of(trace(rule))));
        return element;
    }

    /** 标准化字段夹具 */
    private NormalizedField field(String type) {
        NormalizedField field = new NormalizedField();
        field.setField(type);
        field.setValue("1");
        return field;
    }

    /** 轨迹条目夹具 */
    private TraceEntry trace(String rule) {
        TraceEntry entry = new TraceEntry();
        entry.setRule(rule);
        return entry;
    }

    /**
     * 仅空白改写（原文空白、展示文不同）不算处置：changedCount 不增。
     *
     * <p>守护 {@code affected} 的空白判定：把空白当改写会虚增改写数与页码范围。
     */
    @Test
    void statsShouldNotCountBlankRewriteAsAffected() {
        ViewElement element = new ViewElement();
        element.setElementId("e-1");
        element.setStatus(ViewElementStatus.NORMAL.name());
        element.setNormalizedText("  ");
        element.setDisplayText("显示文本");
        element.setPage(3);

        Map<String, Object> stats = PreprocessStatsSupport.stats(null, null, view(element));

        assertEquals(0, stats.get(PreprocessStatsSupport.KEY_CHANGED_COUNT));
        assertFalse(stats.containsKey(PreprocessStatsSupport.KEY_PAGE_FROM));
    }

    /**
     * 空集合的留存率为 0（不是除零后的负数）。
     *
     * <p>守护 {@code percent} 的 {@code total <= 0} 边界：写成严格小于会除零得到负数。
     */
    @Test
    void statsShouldReportZeroPercentForEmptyView() {
        Map<String, Object> stats = PreprocessStatsSupport.stats(null, null, view());

        assertEquals(0, stats.get(PreprocessStatsSupport.KEY_RETENTION_PERCENT));
        assertEquals(0, stats.get(PreprocessStatsSupport.KEY_ELEMENT_COUNT));
    }
}
