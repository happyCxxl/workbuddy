package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.biz.service.support.index.IndexComboReconciler;
import com.workbuddy.worker.indexing.search.FullTextHit;
import com.workbuddy.worker.indexing.search.FullTextQuery;
import com.workbuddy.worker.indexing.search.VectorHit;
import com.workbuddy.worker.indexing.search.VectorQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.indexSet;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.versionRow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 候选校验单测：一致性对账 + 向量/全文冒烟与不完整组合的缺口陈述。
 */
class IndexBuildValidateTest {

    private IndexBuildFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new IndexBuildFixture();
    }

    /**
     * 校验：对账一致且冒烟命中时通过三项。
     *
     * <p>守护 {@code validate}：对账或冒烟判错即失败。
     */
    @Test
    void validateShouldPassAllChecks() {
        WbIndexVersion version = versionRow(20L, "v2", IndexVersionStatus.READY.name());
        when(fixture.indexVersionDbService.getById(20L)).thenReturn(version);
        when(fixture.indexSetDbService.getById(5L)).thenReturn(indexSet());
        when(fixture.indexComboReconciler.computeExpected(eq(1L), any()))
                .thenReturn(new IndexComboReconciler.ComboExpectation(Set.of("chunk-1"), 1, 1024,
                        true, null, true, null, List.of(1.0f, 2.0f), "投标保证金"));
        when(fixture.milvusIndexPort.listChunkIds("kb_1_v2")).thenReturn(List.of("chunk-1"));
        when(fixture.milvusIndexPort.searchVector(anyString(), any(VectorQuery.class)))
                .thenReturn(List.of(VectorHit.builder().chunkId("chunk-1").build()));
        when(fixture.milvusIndexPort.searchFullText(anyString(), any(FullTextQuery.class)))
                .thenReturn(List.of(FullTextHit.builder().chunkId("chunk-1").build()));

        var result = fixture.service.validate(20L);

        assertTrue(result.isPassed());
        assertEquals(3, result.getItems().size());
        assertTrue(result.getItems().getFirst().isPassed());
    }

    /**
     * 校验：冒烟未命中判失败。
     *
     * <p>守护 {@code validate}：空命中判通过即失败。
     */
    @Test
    void validateShouldFailWhenSmokeMisses() {
        WbIndexVersion version = versionRow(20L, "v2", IndexVersionStatus.READY.name());
        when(fixture.indexVersionDbService.getById(20L)).thenReturn(version);
        when(fixture.indexSetDbService.getById(5L)).thenReturn(indexSet());
        when(fixture.indexComboReconciler.computeExpected(eq(1L), any()))
                .thenReturn(new IndexComboReconciler.ComboExpectation(Set.of("chunk-1"), 1, 1024,
                        true, null, true, null, List.of(1.0f, 2.0f), "投标保证金"));
        when(fixture.milvusIndexPort.listChunkIds("kb_1_v2")).thenReturn(List.of("chunk-1"));
        when(fixture.milvusIndexPort.searchVector(anyString(), any(VectorQuery.class))).thenReturn(List.of());
        when(fixture.milvusIndexPort.searchFullText(anyString(), any(FullTextQuery.class))).thenReturn(List.of());

        var result = fixture.service.validate(20L);

        assertFalse(result.isPassed());
        assertFalse(result.getItems().get(1).isPassed());
        assertFalse(result.getItems().get(2).isPassed());
    }

    /**
     * 校验：组合产物不完整时陈述缺口且不做冒烟。
     *
     * <p>守护 {@code validate}：缺产物仍冒烟即失败。
     */
    @Test
    void validateShouldReportIncompleteCombo() {
        WbIndexVersion version = versionRow(20L, "v2", IndexVersionStatus.READY.name());
        when(fixture.indexVersionDbService.getById(20L)).thenReturn(version);
        when(fixture.indexSetDbService.getById(5L)).thenReturn(indexSet());
        when(fixture.indexComboReconciler.computeExpected(eq(1L), any()))
                .thenReturn(new IndexComboReconciler.ComboExpectation(Set.of(), 0, 0, false,
                        "文件 10 切片血统与组合预处理策略不符", true, null, null, null));

        var result = fixture.service.validate(20L);

        assertFalse(result.isPassed());
        assertFalse(result.getItems().getFirst().isPassed());
        assertTrue(result.getItems().getFirst().getDetail().contains("组合产物不完整"),
                result.getItems().getFirst().getDetail());
        // 必须陈述具体缺口（而不是把 dimError 的 null 拼进去）
        assertTrue(result.getItems().getFirst().getDetail().contains("文件 10 切片血统与组合预处理策略不符"),
                result.getItems().getFirst().getDetail());
        verify(fixture.milvusIndexPort, never()).searchVector(anyString(), any());
        verify(fixture.milvusIndexPort, never()).searchFullText(anyString(), any());
    }

    /**
     * 校验：构建中版本拒绝（40443）。
     *
     * <p>守护 {@code validate}：构建中可校验即失败。
     */
    @Test
    void validateShouldForbidNotReadyVersion() {
        WbIndexVersion version = versionRow(20L, "v2", IndexVersionStatus.BUILDING.name());
        when(fixture.indexVersionDbService.getById(20L)).thenReturn(version);
        when(fixture.indexSetDbService.getById(5L)).thenReturn(indexSet());

        WorkbuddyException ex = assertThrows(WorkbuddyException.class, () -> fixture.service.validate(20L));
        assertEquals(ErrorCode.INDEX_BUILDING_CONFLICT.getCode(), ex.getCode());
    }
}
