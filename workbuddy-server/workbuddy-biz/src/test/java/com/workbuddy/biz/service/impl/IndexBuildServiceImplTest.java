package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.dto.response.index.IndexBuildTriggerVO;
import com.workbuddy.common.enums.index.IndexBuildTrigger;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.worker.indexing.BuildOrder;
import com.workbuddy.worker.indexing.ComboSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.comboAll;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.file;
import static com.workbuddy.biz.service.impl.IndexSetTestFixtures.indexSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 候选构建单测：注册不要求产物齐、绑定补齐、范围校验/归一化、防重 40443 与任务字段落库。
 */
class IndexBuildServiceImplTest {

    private IndexBuildFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new IndexBuildFixture();
    }

    /**
     * COMPENSATE 遇到同组合 READY 活跃版本允许重开（不跳过），NEW 与显式档才跳过。
     *
     * <p>守护 {@code tryBuild} 的触发类型判定：把 COMPENSATE 判成 NEW 会让回退复活后的补齐闭环断掉。
     */
    @Test
    void tryBuildCompensateShouldReopenReadyVersion() {
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        WbIndexVersion ready = IndexSetTestFixtures.versionRow(30L, "v9", IndexVersionStatus.READY.name());
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of(ready));
        when(fixture.indexVersionDbService.nextVersionNo(5L)).thenReturn("v10");
        fixture.stubSavedVersion(101L);
        fixture.stubTaskSave(201L);

        var result = (IndexBuildTriggerVO) ReflectionTestUtils.invokeMethod(
                fixture.service, "tryBuild", 1L, comboAll(), "COMPENSATE", false);

        assertNotNull(result);
        assertEquals("v10", result.getVersionNo());
        verify(fixture.taskQueue).enqueue(201L);
    }

    /**
     * 校验点：{@code buildCandidateShouldRegisterComboWithoutRequiringProducts}。
     */
    @Test
    void buildCandidateShouldRegisterComboWithoutRequiringProducts() {
        // 注册组合不要求产物齐（切策略场景：注册先行、文件重跑累积；缺口由对账任务 FAILED 呈现）
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(fixture.indexVersionDbService.nextVersionNo(5L)).thenReturn("v1");
        fixture.stubSavedVersion(100L);
        fixture.stubTaskSave(200L);

        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        order.setComboSnapshot(comboAll());
        var result = fixture.service.buildCandidate(order);

        assertEquals("v1", result.getVersionNo());
        verify(fixture.pipelineTaskDbService).save(any(WbPipelineTask.class));
        verify(fixture.taskQueue).enqueue(200L);
    }

    /**
     * 手动构建：策略不全时用知识库绑定组合补齐。
     *
     * <p>守护 {@code buildCandidate}：空策略组合被构建即失败。
     */
    @Test
    void buildCandidateWithBlankStrategiesShouldResolveBoundCombo() {
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        when(fixture.indexComboService.resolveBoundCombo(1L)).thenReturn(comboAll());
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(fixture.indexVersionDbService.nextVersionNo(5L)).thenReturn("v1");
        fixture.stubSavedVersion(100L);
        fixture.stubTaskSave(200L);

        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        ComboSnapshot blank = new ComboSnapshot();
        blank.setFileScopeMode("ALL");
        order.setComboSnapshot(blank);

        var result = fixture.service.buildCandidate(order);

        assertEquals("v1", result.getVersionNo());
        ArgumentCaptor<WbPipelineTask> taskCaptor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(fixture.pipelineTaskDbService).save(taskCaptor.capture());
        assertTrue(taskCaptor.getValue().getStrategySnapshot().contains("chunk-hybrid-v1"),
                taskCaptor.getValue().getStrategySnapshot());
    }

    /**
     * 手动构建：未知环节策略按 40001 拒绝。
     *
     * <p>守护 {@code buildCandidate}：坏数据被携带即失败。
     */
    @Test
    void buildCandidateWithUnknownStageShouldReject40001() {
        // 环节白名单防线：stageStrategies 只允许预处理/切片/向量三环节，未知键=坏数据直接拒绝
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        ComboSnapshot combo = comboAll();
        combo.getStageStrategies().put(PipelineStage.PARSE.name(), "parse-x-v1");
        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        order.setComboSnapshot(combo);

        WorkbuddyException ex = assertThrows(WorkbuddyException.class,
                () -> fixture.service.buildCandidate(order));

        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("未知环节策略"), ex.getMessage());
        verify(fixture.indexSetDbService, never()).getOrCreateByKb(anyLong());
    }

    /**
     * 手动构建：同组合活跃版本冲突 40443。
     *
     * <p>守护 {@code buildCandidate}：重复建版本即失败。
     */
    @Test
    void buildCandidateWhenActiveVersionExistsShouldThrow40443() {
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        WbIndexVersion ready = IndexSetTestFixtures.versionRow(90L, "v1", IndexVersionStatus.READY.name());
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of(ready));

        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        order.setComboSnapshot(comboAll());
        WorkbuddyException ex = assertThrows(WorkbuddyException.class,
                () -> fixture.service.buildCandidate(order));
        assertEquals(ErrorCode.INDEX_BUILDING_CONFLICT.getCode(), ex.getCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
    }

    /**
     * 手动构建：LIST 范围缺文件列表按 40001 拒绝。
     *
     * <p>守护 {@code buildCandidate}：空列表被放行即失败。
     */
    @Test
    void buildCandidateShouldRejectListScopeWithoutFiles() {
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        ComboSnapshot combo = comboAll();
        combo.setFileScopeMode("LIST");
        order.setComboSnapshot(combo);

        WorkbuddyException ex = assertThrows(WorkbuddyException.class,
                () -> fixture.service.buildCandidate(order));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    /**
     * 手动构建：文件列表含外库文件按 40001 拒绝。
     *
     * <p>守护 {@code buildCandidate}：外库文件混入即失败。
     */
    @Test
    void buildCandidateShouldRejectForeignFilesInListScope() {
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        when(fixture.fileResultDbService.listByKb(1L)).thenReturn(List.of(file(9L)));
        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        ComboSnapshot combo = comboAll();
        combo.setFileScopeMode("LIST");
        combo.setFileResultIds(List.of(10L));
        order.setComboSnapshot(combo);

        WorkbuddyException ex = assertThrows(WorkbuddyException.class,
                () -> fixture.service.buildCandidate(order));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("非本知识库"), ex.getMessage());
    }

    /**
     * 手动构建：ALL 范围归一化，残留文件列表置空。
     *
     * <p>守护 {@code buildCandidate}：脏范围写进快照即失败。
     */
    @Test
    void buildCandidateShouldNormalizeAllScopeRange() {
        // ALL 范围归一化——残留 fileResultIds 置空，落库快照干净
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(fixture.indexVersionDbService.nextVersionNo(5L)).thenReturn("v1");
        AtomicReference<WbIndexVersion> saved = new AtomicReference<>();
        when(fixture.indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            WbIndexVersion v = inv.getArgument(0);
            saved.set(v);
            return true;
        });
        fixture.stubTaskSave(200L);
        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        ComboSnapshot combo = comboAll();
        combo.setFileResultIds(List.of(10L));
        order.setComboSnapshot(combo);
        order.setTrigger(IndexBuildTrigger.REBUILD.name());

        fixture.service.buildCandidate(order);

        ComboSnapshot savedCombo = JsonUtil.toObject(saved.get().getComboSnapshot(), ComboSnapshot.class);
        assertNotNull(savedCombo);
        assertEquals("ALL", savedCombo.getFileScopeMode());
        assertNull(savedCombo.getFileResultIds());
    }

    /**
     * ALL 范围且范围字段为 null 时也归一化成 ALL。
     *
     * <p>守护 {@code buildCandidate} 的范围归一：残留 null 会写进组合快照，影响组合判等。
     */
    @Test
    void buildCandidateShouldFillNullScopeMode() {
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(fixture.indexVersionDbService.nextVersionNo(5L)).thenReturn("v1");
        AtomicReference<WbIndexVersion> saved = new AtomicReference<>();
        when(fixture.indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            WbIndexVersion v = inv.getArgument(0);
            saved.set(v);
            return true;
        });
        fixture.stubTaskSave(200L);
        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        ComboSnapshot combo = comboAll();
        combo.setFileScopeMode(null);
        combo.setFileResultIds(List.of(10L));
        order.setComboSnapshot(combo);

        fixture.service.buildCandidate(order);

        ComboSnapshot savedCombo = JsonUtil.toObject(saved.get().getComboSnapshot(), ComboSnapshot.class);
        assertEquals("ALL", savedCombo.getFileScopeMode());
        assertNull(savedCombo.getFileResultIds());
    }

    /**
     * 构建命令缺知识库或组合快照一律 40001；知识库不存在按 40401。
     *
     * <p>守护 {@code buildCandidate} 的入参防线：放行空命令会在下游 NPE。
     */
    @Test
    void buildCandidateShouldRejectIncompleteCommand() {
        WorkbuddyException nullOrder = assertThrows(WorkbuddyException.class,
                () -> fixture.service.buildCandidate(null));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), nullOrder.getCode());

        WorkbuddyException noKb = assertThrows(WorkbuddyException.class,
                () -> fixture.service.buildCandidate(new BuildOrder()));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), noKb.getCode());

        BuildOrder noCombo = new BuildOrder();
        noCombo.setKnowledgeBaseId(1L);
        WorkbuddyException comboMissing = assertThrows(WorkbuddyException.class,
                () -> fixture.service.buildCandidate(noCombo));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), comboMissing.getCode());

        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(null);
        BuildOrder unknownKb = new BuildOrder();
        unknownKb.setKnowledgeBaseId(1L);
        unknownKb.setComboSnapshot(comboAll());
        WorkbuddyException notFound = assertThrows(WorkbuddyException.class,
                () -> fixture.service.buildCandidate(unknownKb));
        assertEquals(ErrorCode.KB_NOT_FOUND.getCode(), notFound.getCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
    }

    /**
     * 环节策略不全且知识库没有绑定组合时按 40445 拒绝。
     *
     * <p>守护 {@code buildCandidate} 的绑定兜底：拿不到绑定组合还能继续会构建出空策略组合。
     */
    @Test
    void buildCandidateWithoutStrategiesOrBindingShouldReject() {
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        when(fixture.indexComboService.resolveBoundCombo(1L)).thenReturn(null);
        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        order.setComboSnapshot(new ComboSnapshot());

        WorkbuddyException ex = assertThrows(WorkbuddyException.class,
                () -> fixture.service.buildCandidate(order));

        assertEquals(ErrorCode.INDEX_COMBO_INCOMPLETE.getCode(), ex.getCode());
        verify(fixture.pipelineTaskDbService, never()).save(any());
    }

    /**
     * LIST 范围且文件全属本库：不触发「非本库文件」拒绝。
     *
     * <p>守护 {@code buildCandidate} 的归属过滤：过滤条件写反会把本库文件当外库。
     */
    @Test
    void buildCandidateWithOwnFilesInListScopeShouldAccept() {
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        when(fixture.fileResultDbService.listByKb(1L)).thenReturn(List.of(file(10L), file(20L)));
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(fixture.indexVersionDbService.nextVersionNo(5L)).thenReturn("v1");
        fixture.stubSavedVersion(100L);
        fixture.stubTaskSave(200L);
        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        ComboSnapshot combo = comboAll();
        combo.setFileScopeMode("LIST");
        combo.setFileResultIds(List.of(10L, 20L));
        order.setComboSnapshot(combo);

        var result = fixture.service.buildCandidate(order);

        assertEquals("v1", result.getVersionNo());
        verify(fixture.taskQueue).enqueue(200L);
    }

    /**
     * 建版本行与 BUILD_INDEX 任务：两行的关键列逐列落库，命令快照可回读。
     *
     * <p>守护 {@code createVersionAndTask} 的字段搬运：任务缺 stage/status 就永远不会被消费。
     */
    @Test
    void createVersionAndTaskShouldPersistVersionAndTaskFields() {
        when(fixture.knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        when(fixture.indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(fixture.indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(fixture.indexVersionDbService.nextVersionNo(5L)).thenReturn("v4");
        AtomicReference<WbIndexVersion> saved = new AtomicReference<>();
        when(fixture.indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            WbIndexVersion v = inv.getArgument(0);
            v.setId(104L);
            saved.set(v);
            return true;
        });
        fixture.stubTaskSave(204L);
        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        order.setComboSnapshot(comboAll());

        var result = fixture.service.buildCandidate(order);

        ArgumentCaptor<WbPipelineTask> taskCaptor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(fixture.pipelineTaskDbService).save(taskCaptor.capture());
        WbPipelineTask task = taskCaptor.getValue();
        assertEquals(PipelineStage.BUILD_INDEX.name(), task.getStage());
        assertEquals(PipelineTaskStatus.QUEUED.name(), task.getStatus());
        BuildOrder snapshot = JsonUtil.toObject(task.getStrategySnapshot(), BuildOrder.class);
        assertEquals(1L, snapshot.getKnowledgeBaseId());
        assertEquals(IndexBuildTrigger.REBUILD.name(), snapshot.getTrigger());

        WbIndexVersion version = saved.get();
        assertEquals(5L, version.getIndexSetId());
        assertEquals("v4", version.getVersionNo());
        assertEquals(IndexVersionStatus.CREATED.name(), version.getStatus());
        assertEquals(204L, version.getTaskId());
        assertEquals(104L, result.getVersionId());
        assertEquals("v4", result.getVersionNo());
        assertEquals(204L, result.getPipelineTaskId());
        verify(fixture.taskQueue).enqueue(204L);
    }
}
