package com.workbuddy.biz.task.runner;

import com.workbuddy.biz.service.IndexSetService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.support.IndexComboReconciler;
import com.workbuddy.biz.service.support.IndexRowAssembler;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.enums.index.IndexBuildTrigger;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.worker.indexing.BuildOrder;
import com.workbuddy.worker.indexing.ComboSnapshot;
import com.workbuddy.worker.indexing.IndexRow;
import com.workbuddy.worker.indexing.MilvusIndexPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 索引构建任务单测（注册+对账语义，期望值来自 IndexComboReconciler；
 * 评测冻结集回填/永不自动发布）：
 * 组合缺口 FAILED / 维度不一致 FAILED / 血统不符 FAILED / 集合初始化失败 FAILED /
 * 一致性失败保留集合 FAILED / 对账通过 READY（COMPENSATE 自动发布、REBUILD 停留、INCREMENT 绑定开自动、统计收敛）/
 * LIST 冻结集回填后 READY 且任何触发都不自动发布。
 */
@ExtendWith(MockitoExtension.class)
class IndexBuildTaskRunnerTest {

    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private WbIndexVersionDbService indexVersionDbService;
    @Mock
    private WbKnowledgeBaseDbService knowledgeBaseDbService;
    @Mock
    private WbFileResultDbService fileResultDbService;
    @Mock
    private MilvusIndexPort milvusIndexPort;
    @Mock
    private IndexSetService indexSetService;
    @Mock
    private IndexComboReconciler indexComboReconciler;
    @Mock
    private IndexRowAssembler indexRowAssembler;

    private IndexBuildTaskRunner runner;

    @BeforeEach
    void setUp() {
        runner = new IndexBuildTaskRunner(pipelineTaskDbService, indexVersionDbService,
                knowledgeBaseDbService, fileResultDbService, milvusIndexPort, indexSetService,
                indexComboReconciler, indexRowAssembler);
    }

    private ComboSnapshot comboAll() {
        return ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
    }

    private WbPipelineTask queuedBuildTask(String trigger) {
        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        order.setComboSnapshot(comboAll());
        order.setTrigger(trigger);
        WbPipelineTask task = new WbPipelineTask();
        task.setId(1L);
        task.setStage(PipelineStage.BUILD_INDEX.name());
        task.setStatus(PipelineTaskStatus.QUEUED.name());
        task.setStrategySnapshot(JsonUtil.toJsonStr(order));
        return task;
    }

    /** LIST 冻结集构建任务：范围 = 文件 10 */
    private WbPipelineTask queuedListBuildTask(String trigger) {
        ComboSnapshot combo = comboAll();
        combo.setFileScopeMode("LIST");
        combo.setFileResultIds(new ArrayList<>(List.of(10L)));
        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        order.setComboSnapshot(combo);
        order.setTrigger(trigger);
        WbPipelineTask task = new WbPipelineTask();
        task.setId(1L);
        task.setStage(PipelineStage.BUILD_INDEX.name());
        task.setStatus(PipelineTaskStatus.QUEUED.name());
        task.setStrategySnapshot(JsonUtil.toJsonStr(order));
        return task;
    }

    private WbIndexVersion versionRow() {
        WbIndexVersion version = new WbIndexVersion();
        version.setId(100L);
        version.setVersionNo("v1");
        version.setStatus(IndexVersionStatus.CREATED.name());
        return version;
    }

    private IndexComboReconciler.ComboExpectation completeExpectation() {
        return new IndexComboReconciler.ComboExpectation(Set.of("chunk-1"), 1, 1024,
                true, null, true, null, List.of(1.0f, 2.0f), "投标保证金");
    }

    /** 冻结集回填桩：范围文件 10 的切片/向量产物齐 + 装配一行 */
    private void stubFrozenBackfill(WbChunkSet chunkRow, WbEmbeddingSet embedRow) {
        when(indexComboReconciler.latestChunkMap(List.of(10L)))
                .thenReturn(Map.of("10#chunk-hybrid-v1", chunkRow));
        when(indexComboReconciler.latestEmbedMap(List.of(10L)))
                .thenReturn(Map.of("10#embed-default-v1", embedRow));
        when(indexComboReconciler.selectComboProducts(any(ComboSnapshot.class), eq(10L), anyMap(), anyMap()))
                .thenReturn(new IndexComboReconciler.ComboProducts(chunkRow, embedRow, null));
        WbFileResult file = new WbFileResult();
        file.setId(10L);
        file.setOwner("userA");
        when(fileResultDbService.getById(10L)).thenReturn(file);
        IndexRow row = new IndexRow();
        row.setChunkId("chunk-1");
        when(indexRowAssembler.assemble(eq(10L), eq("userA"), eq(chunkRow), eq(embedRow)))
                .thenReturn(List.of(row));
    }

    /**
     * 守护 {@code runShouldFailWhenComboIncomplete}：该场景行为改动即失败。
     */
    @Test
    void runShouldFailWhenComboIncomplete() {
        WbPipelineTask task = queuedBuildTask(IndexBuildTrigger.INCREMENT.name());
        WbIndexVersion version = versionRow();
        when(pipelineTaskDbService.getById(1L)).thenReturn(task);
        when(pipelineTaskDbService.claim(1L)).thenReturn(1);
        when(indexVersionDbService.getByTaskId(1L)).thenReturn(version);
        when(indexComboReconciler.computeExpected(eq(1L), any(ComboSnapshot.class)))
                .thenReturn(new IndexComboReconciler.ComboExpectation(Set.of(), 0, 0, false,
                        "文件 10 缺少组合产物（切片策略 chunk-hybrid-v1 / 向量策略 embed-default-v1）",
                        true, null, null, null));

        runner.run(1L);

        assertEquals(IndexVersionStatus.FAILED.name(), version.getStatus());
        assertTrue(version.getBuildError().contains("缺少组合产物"), version.getBuildError());
        verify(pipelineTaskDbService).finish(1L, PipelineTaskStatus.FAILED,
                PipelineTaskErrorCode.INDEX_INCOMPLETE.name(), version.getBuildError());
        verify(milvusIndexPort, never()).ensureCollection(anyString(), any(Integer.class));
        verify(milvusIndexPort, never()).listChunkIds(anyString());
    }

    /**
     * 守护 {@code runShouldFailWhenLineageMismatch}：该场景行为改动即失败。
     */
    @Test
    void runShouldFailWhenLineageMismatch() {
        WbPipelineTask task = queuedBuildTask(IndexBuildTrigger.INCREMENT.name());
        WbIndexVersion version = versionRow();
        when(pipelineTaskDbService.getById(1L)).thenReturn(task);
        when(pipelineTaskDbService.claim(1L)).thenReturn(1);
        when(indexVersionDbService.getByTaskId(1L)).thenReturn(version);
        when(indexComboReconciler.computeExpected(eq(1L), any(ComboSnapshot.class)))
                .thenReturn(new IndexComboReconciler.ComboExpectation(Set.of(), 0, 0, false,
                        "文件 10 切片血统与组合预处理策略不符", true, null, null, null));

        runner.run(1L);

        assertEquals(IndexVersionStatus.FAILED.name(), version.getStatus());
        assertTrue(version.getBuildError().contains("血统与组合预处理策略不符"), version.getBuildError());
        verify(pipelineTaskDbService).finish(1L, PipelineTaskStatus.FAILED,
                PipelineTaskErrorCode.INDEX_INCOMPLETE.name(), version.getBuildError());
    }

    /**
     * 守护 {@code runShouldFailWhenDimensionMismatch}：该场景行为改动即失败。
     */
    @Test
    void runShouldFailWhenDimensionMismatch() {
        WbPipelineTask task = queuedBuildTask(IndexBuildTrigger.INCREMENT.name());
        WbIndexVersion version = versionRow();
        when(pipelineTaskDbService.getById(1L)).thenReturn(task);
        when(pipelineTaskDbService.claim(1L)).thenReturn(1);
        when(indexVersionDbService.getByTaskId(1L)).thenReturn(version);
        when(indexComboReconciler.computeExpected(eq(1L), any(ComboSnapshot.class)))
                .thenReturn(new IndexComboReconciler.ComboExpectation(Set.of(), 0, 0, true, null, false,
                        "向量维度不一致: [512, 1024]", null, null));

        runner.run(1L);

        assertEquals(IndexVersionStatus.FAILED.name(), version.getStatus());
        assertTrue(version.getBuildError().contains("维度不一致"), version.getBuildError());
        verify(pipelineTaskDbService).finish(1L, PipelineTaskStatus.FAILED,
                PipelineTaskErrorCode.INDEX_DIMENSION_MISMATCH.name(), version.getBuildError());
    }

    /**
     * 守护 {@code runShouldFailConsistencyAndKeepCollection}：该场景行为改动即失败。
     */
    @Test
    void runShouldFailConsistencyAndKeepCollection() {
        WbPipelineTask task = queuedBuildTask(IndexBuildTrigger.INCREMENT.name());
        WbIndexVersion version = versionRow();
        when(pipelineTaskDbService.getById(1L)).thenReturn(task);
        when(pipelineTaskDbService.claim(1L)).thenReturn(1);
        when(indexVersionDbService.getByTaskId(1L)).thenReturn(version);
        when(indexComboReconciler.computeExpected(eq(1L), any(ComboSnapshot.class)))
                .thenReturn(completeExpectation());
        when(milvusIndexPort.listChunkIds("kb_1_v1")).thenReturn(List.of("chunk-1", "ghost"));

        runner.run(1L);

        verify(milvusIndexPort).ensureCollection("kb_1_v1", 1024);
        verify(milvusIndexPort).load("kb_1_v1");
        verify(milvusIndexPort, never()).drop(anyString());
        assertEquals(IndexVersionStatus.FAILED.name(), version.getStatus());
        assertTrue(version.getBuildError().contains("一致性校验失败"), version.getBuildError());
        verify(pipelineTaskDbService).finish(1L, PipelineTaskStatus.FAILED,
                PipelineTaskErrorCode.INDEX_CONSISTENCY_FAILED.name(), version.getBuildError());
        verify(indexSetService, never()).publish(anyLong());
    }

    /**
     * 守护 {@code runShouldFailWhenCollectionInitThrows}：该场景行为改动即失败。
     */
    @Test
    void runShouldFailWhenCollectionInitThrows() {
        WbPipelineTask task = queuedBuildTask(IndexBuildTrigger.INCREMENT.name());
        WbIndexVersion version = versionRow();
        when(pipelineTaskDbService.getById(1L)).thenReturn(task);
        when(pipelineTaskDbService.claim(1L)).thenReturn(1);
        when(indexVersionDbService.getByTaskId(1L)).thenReturn(version);
        when(indexComboReconciler.computeExpected(eq(1L), any(ComboSnapshot.class)))
                .thenReturn(completeExpectation());
        doThrow(new KnowledgeException(ErrorCode.INDEX_COLLECTION_SCHEMA_MISMATCH, "维度不一致"))
                .when(milvusIndexPort).ensureCollection("kb_1_v1", 1024);

        runner.run(1L);

        assertEquals(IndexVersionStatus.FAILED.name(), version.getStatus());
        assertTrue(version.getBuildError().contains("集合初始化失败"), version.getBuildError());
        verify(pipelineTaskDbService).finish(1L, PipelineTaskStatus.FAILED,
                PipelineTaskErrorCode.INDEX_BUILD_FAILED.name(), "维度不一致");
    }

    /**
     * 守护 {@code runShouldBecomeReadyAndAutoPublishOnCompensate}：该场景行为改动即失败。
     */
    @Test
    void runShouldBecomeReadyAndAutoPublishOnCompensate() {
        WbPipelineTask task = queuedBuildTask(IndexBuildTrigger.COMPENSATE.name());
        WbIndexVersion version = versionRow();
        when(pipelineTaskDbService.getById(1L)).thenReturn(task);
        when(pipelineTaskDbService.claim(1L)).thenReturn(1);
        when(indexVersionDbService.getByTaskId(1L)).thenReturn(version);
        when(indexComboReconciler.computeExpected(eq(1L), any(ComboSnapshot.class)))
                .thenReturn(completeExpectation());
        when(milvusIndexPort.listChunkIds("kb_1_v1")).thenReturn(List.of("chunk-1"));

        runner.run(1L);

        assertEquals(IndexVersionStatus.READY.name(), version.getStatus());
        assertNotNull(version.getValidatedAt());
        assertEquals(1, version.getChunkCount());
        assertEquals(1, version.getVectorCount());
        verify(pipelineTaskDbService).finish(1L, PipelineTaskStatus.SUCCESS, null, null);
        verify(indexSetService).publish(100L);
    }

    /**
     * 守护 {@code runShouldBecomeReadyButNotPublishOnRebuild}：该场景行为改动即失败。
     */
    @Test
    void runShouldBecomeReadyButNotPublishOnRebuild() {
        WbPipelineTask task = queuedBuildTask(IndexBuildTrigger.REBUILD.name());
        WbIndexVersion version = versionRow();
        when(pipelineTaskDbService.getById(1L)).thenReturn(task);
        when(pipelineTaskDbService.claim(1L)).thenReturn(1);
        when(indexVersionDbService.getByTaskId(1L)).thenReturn(version);
        when(indexComboReconciler.computeExpected(eq(1L), any(ComboSnapshot.class)))
                .thenReturn(completeExpectation());
        when(milvusIndexPort.listChunkIds("kb_1_v1")).thenReturn(List.of("chunk-1"));

        runner.run(1L);

        assertEquals(IndexVersionStatus.READY.name(), version.getStatus());
        verify(pipelineTaskDbService).finish(1L, PipelineTaskStatus.SUCCESS, null, null);
        verify(indexSetService, never()).publish(anyLong());
    }

    /**
     * 守护 {@code runShouldBecomeReadyAndAutoPublishOnIncrementWithBindingOn}：该场景行为改动即失败。
     */
    @Test
    void runShouldBecomeReadyAndAutoPublishOnIncrementWithBindingOn() {
        WbPipelineTask task = queuedBuildTask(IndexBuildTrigger.INCREMENT.name());
        WbIndexVersion version = versionRow();
        when(pipelineTaskDbService.getById(1L)).thenReturn(task);
        when(pipelineTaskDbService.claim(1L)).thenReturn(1);
        when(indexVersionDbService.getByTaskId(1L)).thenReturn(version);
        when(indexComboReconciler.computeExpected(eq(1L), any(ComboSnapshot.class)))
                .thenReturn(completeExpectation());
        when(milvusIndexPort.listChunkIds("kb_1_v1")).thenReturn(List.of("chunk-1"));
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(1L);
        kb.setStrategyBindingEnabled(1);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(kb);

        runner.run(1L);

        verify(indexSetService).publish(100L);
        org.junit.jupiter.api.Assertions.assertTrue(org.mockito.Mockito.mockingDetails(indexSetService).getInvocations().stream()
                .anyMatch(inv -> "publish".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code runShouldBackfillFrozenScopeAndBecomeReady}：该场景行为改动即失败。
     */
    @Test
    void runShouldBackfillFrozenScopeAndBecomeReady() {
        // LIST 冻结集构建 → 回填范围文件行 → 对账通过 → READY（REBUILD 停留不发布）
        WbPipelineTask task = queuedListBuildTask(IndexBuildTrigger.REBUILD.name());
        WbIndexVersion version = versionRow();
        when(pipelineTaskDbService.getById(1L)).thenReturn(task);
        when(pipelineTaskDbService.claim(1L)).thenReturn(1);
        when(indexVersionDbService.getByTaskId(1L)).thenReturn(version);
        when(indexComboReconciler.computeExpected(eq(1L), any(ComboSnapshot.class)))
                .thenReturn(completeExpectation());
        WbChunkSet chunkRow = new WbChunkSet();
        chunkRow.setId(1L);
        chunkRow.setFileResultId(10L);
        chunkRow.setChunkStrategyVersion("chunk-hybrid-v1");
        chunkRow.setUpstreamProductId(100L);
        WbEmbeddingSet embedRow = new WbEmbeddingSet();
        embedRow.setId(2L);
        embedRow.setFileResultId(10L);
        embedRow.setStrategyVersion("embed-default-v1");
        stubFrozenBackfill(chunkRow, embedRow);
        when(milvusIndexPort.listChunkIds("kb_1_v1")).thenReturn(List.of("chunk-1"));

        runner.run(1L);

        verify(milvusIndexPort).append(eq("kb_1_v1"), anyList());
        assertEquals(IndexVersionStatus.READY.name(), version.getStatus());
        assertEquals(1, version.getChunkCount());
        assertEquals(1, version.getVectorCount());
        verify(pipelineTaskDbService).finish(1L, PipelineTaskStatus.SUCCESS, null, null);
        verify(indexSetService, never()).publish(anyLong());
    }

    /**
     * 守护 {@code runShouldNotAutoPublishFrozenScopeEvenOnCompensate}：该场景行为改动即失败。
     */
    @Test
    void runShouldNotAutoPublishFrozenScopeEvenOnCompensate() {
        // LIST 冻结集永不自动发布——即使 COMPENSATE 触发也停留 READY
        WbPipelineTask task = queuedListBuildTask(IndexBuildTrigger.COMPENSATE.name());
        WbIndexVersion version = versionRow();
        when(pipelineTaskDbService.getById(1L)).thenReturn(task);
        when(pipelineTaskDbService.claim(1L)).thenReturn(1);
        when(indexVersionDbService.getByTaskId(1L)).thenReturn(version);
        when(indexComboReconciler.computeExpected(eq(1L), any(ComboSnapshot.class)))
                .thenReturn(completeExpectation());
        WbChunkSet chunkRow = new WbChunkSet();
        chunkRow.setId(1L);
        chunkRow.setFileResultId(10L);
        chunkRow.setChunkStrategyVersion("chunk-hybrid-v1");
        chunkRow.setUpstreamProductId(100L);
        WbEmbeddingSet embedRow = new WbEmbeddingSet();
        embedRow.setId(2L);
        embedRow.setFileResultId(10L);
        embedRow.setStrategyVersion("embed-default-v1");
        stubFrozenBackfill(chunkRow, embedRow);
        when(milvusIndexPort.listChunkIds("kb_1_v1")).thenReturn(List.of("chunk-1"));

        runner.run(1L);

        assertEquals(IndexVersionStatus.READY.name(), version.getStatus());
        verify(pipelineTaskDbService).finish(1L, PipelineTaskStatus.SUCCESS, null, null);
        verify(indexSetService, never()).publish(anyLong());
    }
}
