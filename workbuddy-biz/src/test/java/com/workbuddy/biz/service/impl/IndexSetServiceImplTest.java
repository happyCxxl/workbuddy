package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.ChunkControlService;
import com.workbuddy.biz.service.IndexComboService;
import com.workbuddy.biz.service.IndexSetService;
import com.workbuddy.biz.service.PreprocessControlService;
import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.support.IndexComboReconciler;
import com.workbuddy.biz.service.support.IndexLineageResolver;
import com.workbuddy.biz.service.support.IndexRowAssembler;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.dto.response.index.IndexComboVO;
import com.workbuddy.common.enums.index.IndexBuildTrigger;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.worker.indexing.BuildOrder;
import com.workbuddy.worker.indexing.ComboSnapshot;
import com.workbuddy.worker.indexing.IndexRow;
import com.workbuddy.worker.indexing.search.FullTextHit;
import com.workbuddy.worker.indexing.search.FullTextQuery;
import com.workbuddy.worker.indexing.MilvusIndexPort;
import com.workbuddy.worker.indexing.search.VectorHit;
import com.workbuddy.worker.indexing.search.VectorQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 索引构建与发布控制面单测（评测冻结集口径）：
 * 产物就绪回调（绑定开 INCREMENT / 绑定关 NEW 候选）/ 显式构建 40444、40443 /
 * 发布三级指针 + 旧版退役 + 审计 / 回退指针切回 + 补齐（无差异 COMPENSATE / 有差异 CHUNK 投递）/
 * currentPublished / 回收（在线禁删、正常回收）/
 * 冻结集（追加路由范围感知、LIST 禁发布禁回退 40449、构建范围校验与 ALL 归一化）。
 */
@ExtendWith(MockitoExtension.class)
class IndexSetServiceImplTest {

    @Mock
    private WbKnowledgeBaseDbService knowledgeBaseDbService;
    @Mock
    private WbIndexSetDbService indexSetDbService;
    @Mock
    private WbIndexVersionDbService indexVersionDbService;
    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private WbFileResultDbService fileResultDbService;
    @Mock
    private WbChunkSetDbService chunkSetDbService;
    @Mock
    private WbEmbeddingSetDbService embeddingSetDbService;
    @Mock
    private WbPipelineStrategyVersionDbService strategyVersionDbService;
    @Mock
    private WbAuditLogDbService kbAuditLogDbService;
    @Mock
    private IndexComboService indexComboService;
    @Mock
    private ChunkControlService chunkControlService;
    @Mock
    private PreprocessControlService preprocessControlService;
    @Mock
    private WbPipelineProductDbService productDbService;
    @Mock
    private IndexLineageResolver lineageResolver;
    @Mock
    private IndexRowAssembler indexRowAssembler;
    @Mock
    private IndexComboReconciler indexComboReconciler;
    @Mock
    private MilvusIndexPort milvusIndexPort;
    @Mock
    private TaskQueueSupport taskQueue;

    private IndexSetServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<IndexSetService> selfProvider = mock(ObjectProvider.class);
        service = new IndexSetServiceImpl(knowledgeBaseDbService, indexSetDbService, indexVersionDbService,
                pipelineTaskDbService, fileResultDbService, chunkSetDbService, embeddingSetDbService,
                strategyVersionDbService, kbAuditLogDbService, indexComboService, chunkControlService,
                preprocessControlService, productDbService, lineageResolver, indexRowAssembler,
                indexComboReconciler, milvusIndexPort, taskQueue, selfProvider);
        lenient().when(selfProvider.getObject()).thenReturn(service);
    }

    private ComboSnapshot comboAll() {
        return ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
    }

    private WbFileResult file(Long id) {
        WbFileResult file = new WbFileResult();
        file.setId(id);
        file.setKnowledgeBaseId(1L);
        file.setOwner("userA");
        return file;
    }

    private WbIndexSet indexSet() {
        WbIndexSet set = new WbIndexSet();
        set.setId(5L);
        set.setKnowledgeBaseId(1L);
        return set;
    }

    private void stubCompleteCombo(WbFileResult file) {
        when(fileResultDbService.listByKb(1L)).thenReturn(List.of(file));
        WbChunkSet chunkSet = new WbChunkSet();
        chunkSet.setId(1L);
        chunkSet.setFileResultId(file.getId());
        chunkSet.setChunkStrategyVersion("chunk-hybrid-v1");
        chunkSet.setUpstreamProductId(100L);
        when(chunkSetDbService.listByFileResultIds(List.of(file.getId()))).thenReturn(List.of(chunkSet));
        WbEmbeddingSet embedSet = new WbEmbeddingSet();
        embedSet.setId(2L);
        embedSet.setFileResultId(file.getId());
        embedSet.setStrategyVersion("embed-default-v1");
        when(embeddingSetDbService.listByFileResultIds(List.of(file.getId()))).thenReturn(List.of(embedSet));
        lenient().when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
    }

    private IndexRow indexRow() {
        IndexRow row = new IndexRow();
        row.setChunkId("chunk-1");
        row.setDocumentId(10L);
        row.setOwner("userA");
        row.setContentType("PARAGRAPH");
        row.setContent("投标保证金叁万元");
        row.setVector(List.of(1.0f, 2.0f));
        return row;
    }

    @Test
    void onFileProductsReadyWhenBindingOnShouldAppendAndAutoPublish() {
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(1L);
        kb.setStrategyBindingEnabled(1);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(kb);
        // 血缘：embed 行 → chunkSetRef 切片行 → 上游预处理策略
        WbEmbeddingSet embedRow = new WbEmbeddingSet();
        embedRow.setId(2L);
        embedRow.setFileResultId(10L);
        embedRow.setChunkSetRef(1L);
        embedRow.setStrategyVersion("embed-default-v1");
        embedRow.setDimension(1024);
        when(embeddingSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(embedRow));
        WbChunkSet chunkRow = new WbChunkSet();
        chunkRow.setId(1L);
        chunkRow.setFileResultId(10L);
        chunkRow.setChunkStrategyVersion("chunk-hybrid-v1");
        chunkRow.setUpstreamProductId(100L);
        when(chunkSetDbService.getById(1L)).thenReturn(chunkRow);
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        when(indexRowAssembler.assemble(eq(10L), eq("userA"), eq(chunkRow), eq(embedRow)))
                .thenReturn(List.of(indexRow()));
        // 注册组合
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(indexVersionDbService.nextVersionNo(5L)).thenReturn("v1");
        AtomicReference<WbIndexVersion> savedVersion = new AtomicReference<>();
        when(indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            WbIndexVersion v = inv.getArgument(0);
            v.setId(100L);
            savedVersion.set(v);
            return true;
        });
        // 对账
        when(milvusIndexPort.listChunkIds("kb_1_v1", 10L)).thenReturn(List.of("chunk-1"));
        // 发布：绑定组合 == 产物组合；publish 内部读回版本行
        when(indexComboService.resolveBoundCombo(1L)).thenReturn(comboAll());
        WbIndexSet set = indexSet();
        when(indexSetDbService.getById(5L)).thenReturn(set);
        when(indexVersionDbService.getById(100L)).thenAnswer(inv -> savedVersion.get());
        // 规则三：无在线版本 → 不补齐
        when(indexSetDbService.getByKb(1L)).thenReturn(indexSet());

        service.onFileProductsReady(10L);

        verify(milvusIndexPort).ensureCollection("kb_1_v1", 1024);
        verify(milvusIndexPort).load("kb_1_v1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<IndexRow>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(milvusIndexPort).append(eq("kb_1_v1"), rowsCaptor.capture());
        assertEquals(1, rowsCaptor.getValue().size());
        assertEquals("chunk-1", rowsCaptor.getValue().getFirst().getChunkId());
        // 活账本 + 发布 → ONLINE
        assertEquals(1, savedVersion.get().getChunkCount());
        assertEquals(1, savedVersion.get().getVectorCount());
        assertEquals(IndexVersionStatus.ONLINE.name(), savedVersion.get().getStatus());
        verify(indexSetDbService).updatePublishedVersion(5L, 100L);
        verify(kbAuditLogDbService).saveAudit(AuditActionType.PUBLISH_INDEX, "INDEX_VERSION", 100L, null, "v1");
    }

    @Test
    void onFileProductsReadyWhenBindingOffShouldSkipAutoRegister() {
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(1L);
        kb.setStrategyBindingEnabled(0);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(kb);
        WbEmbeddingSet embedRow = new WbEmbeddingSet();
        embedRow.setId(2L);
        embedRow.setFileResultId(10L);
        embedRow.setChunkSetRef(1L);
        embedRow.setStrategyVersion("embed-default-v1");
        embedRow.setDimension(1024);
        when(embeddingSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(embedRow));
        WbChunkSet chunkRow = new WbChunkSet();
        chunkRow.setId(1L);
        chunkRow.setFileResultId(10L);
        chunkRow.setChunkStrategyVersion("chunk-hybrid-v1");
        chunkRow.setUpstreamProductId(100L);
        when(chunkSetDbService.getById(1L)).thenReturn(chunkRow);
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        when(indexRowAssembler.assemble(eq(10L), eq("userA"), eq(chunkRow), eq(embedRow)))
                .thenReturn(List.of(indexRow()));
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        // 规则三 / 冻结集路由用
        when(indexSetDbService.getByKb(1L)).thenReturn(indexSet());

        service.onFileProductsReady(10L);

        // 评测模式（绑定关）：不自动注册组合、不建集合、不追加
        verify(indexVersionDbService, never()).save(any(WbIndexVersion.class));
        verify(milvusIndexPort, never()).ensureCollection(anyString(), anyInt());
        verify(milvusIndexPort, never()).load(anyString());
        verify(milvusIndexPort, never()).append(anyString(), anyList());
    }

    @Test
    void onFileProductsReadyWhenBindingOffShouldAppendToExistingActiveVersion() {
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(1L);
        kb.setStrategyBindingEnabled(0);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(kb);
        WbEmbeddingSet embedRow = new WbEmbeddingSet();
        embedRow.setId(2L);
        embedRow.setFileResultId(10L);
        embedRow.setChunkSetRef(1L);
        embedRow.setStrategyVersion("embed-default-v1");
        embedRow.setDimension(1024);
        when(embeddingSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(embedRow));
        WbChunkSet chunkRow = new WbChunkSet();
        chunkRow.setId(1L);
        chunkRow.setFileResultId(10L);
        chunkRow.setChunkStrategyVersion("chunk-hybrid-v1");
        chunkRow.setUpstreamProductId(100L);
        when(chunkSetDbService.getById(1L)).thenReturn(chunkRow);
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        when(indexRowAssembler.assemble(eq(10L), eq("userA"), eq(chunkRow), eq(embedRow)))
                .thenReturn(List.of(indexRow()));
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        // 手动构建已产出的同组合 READY 版本：回调仍应追加（评测模式只关"自动注册"，不关追加）
        WbIndexVersion ready = new WbIndexVersion();
        ready.setId(90L);
        ready.setVersionNo("v1");
        ready.setStatus(IndexVersionStatus.READY.name());
        ready.setComboSnapshot(JsonUtil.toJsonStr(comboAll()));
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of(ready));
        when(milvusIndexPort.listChunkIds("kb_1_v1", 10L)).thenReturn(List.of("chunk-1"));
        when(indexSetDbService.getByKb(1L)).thenReturn(indexSet());

        service.onFileProductsReady(10L);

        verify(milvusIndexPort).append(eq("kb_1_v1"), anyList());
        verify(indexVersionDbService, never()).save(any(WbIndexVersion.class));
        verify(milvusIndexPort, never()).ensureCollection(anyString(), anyInt());
        assertEquals(1, ready.getChunkCount());
    }

    @Test
    void onFileProductsReadyWhenProductComboDiffersFromOnlineShouldBackfillByTopology() {
        // 竞态：切到 v2 后，文件按旧组合（v2 组合）完成 → 规则一追加进 v2 集合，规则三按在线组合（v1）补齐
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(1L);
        kb.setStrategyBindingEnabled(1);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(kb);
        WbEmbeddingSet embedRow = new WbEmbeddingSet();
        embedRow.setId(2L);
        embedRow.setFileResultId(10L);
        embedRow.setChunkSetRef(1L);
        embedRow.setStrategyVersion("embed-default-v2");
        embedRow.setDimension(1024);
        when(embeddingSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(embedRow));
        WbChunkSet chunkRow = new WbChunkSet();
        chunkRow.setId(1L);
        chunkRow.setFileResultId(10L);
        chunkRow.setChunkStrategyVersion("chunk-hybrid-v2");
        chunkRow.setUpstreamProductId(100L);
        when(chunkSetDbService.getById(1L)).thenReturn(chunkRow);
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v2");
        when(indexRowAssembler.assemble(eq(10L), eq("userA"), eq(chunkRow), eq(embedRow)))
                .thenReturn(List.of(indexRow()));
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(indexVersionDbService.nextVersionNo(5L)).thenReturn("v1");
        when(indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            inv.<WbIndexVersion>getArgument(0).setId(100L);
            return true;
        });
        when(milvusIndexPort.listChunkIds("kb_1_v1", 10L)).thenReturn(List.of("chunk-1"));
        // 在线版本 = v1 组合（与产物组合不同）
        WbIndexSet onlineSet = indexSet();
        onlineSet.setCurrentPublishedVersionId(50L);
        when(indexSetDbService.getByKb(1L)).thenReturn(onlineSet);
        WbIndexVersion onlineVersion = new WbIndexVersion();
        onlineVersion.setId(50L);
        onlineVersion.setVersionNo("v0");
        onlineVersion.setComboSnapshot(JsonUtil.toJsonStr(comboAll()));
        when(indexVersionDbService.getById(50L)).thenReturn(onlineVersion);
        // 补齐：目标预处理策略行
        WbPipelineStrategyVersion strategyRow = new WbPipelineStrategyVersion();
        strategyRow.setId(77L);
        when(strategyVersionDbService.getByTypeAndNameAndVersion("PREPROCESS", "preproc-default", "v1"))
                .thenReturn(strategyRow);
        // 绑定组合（v2 口径）≠ 产物组合 → 不自动发布
        when(indexComboService.resolveBoundCombo(1L))
                .thenReturn(ComboSnapshot.of("preproc-default-v2", "chunk-hybrid-v2", "embed-default-v2"));

        service.onFileProductsReady(10L);

        // 规则一：追加进产物组合集合（v1 编号）
        verify(milvusIndexPort).append(eq("kb_1_v1"), anyList());
        // 规则三：按在线组合拓扑重放（缺预处理 → 投递预处理）
        verify(preprocessControlService).preprocess(10L, 77L, null);
        verify(indexSetDbService, never()).updatePublishedVersion(anyLong(), anyLong());
    }

    @Test
    void buildCandidateShouldRegisterComboWithoutRequiringProducts() {
        // 注册组合不要求产物齐（切策略场景：注册先行、文件重跑累积；缺口由对账任务 FAILED 呈现）
        when(knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(indexVersionDbService.nextVersionNo(5L)).thenReturn("v1");
        when(indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            inv.<WbIndexVersion>getArgument(0).setId(100L);
            return true;
        });
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.<WbPipelineTask>getArgument(0).setId(200L);
            return true;
        });

        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        order.setComboSnapshot(comboAll());
        var result = service.buildCandidate(order);

        assertEquals("v1", result.getVersionNo());
        verify(pipelineTaskDbService).save(any(WbPipelineTask.class));
        verify(taskQueue).enqueue(200L);
    }

    @Test
    void buildCandidateWithBlankStrategiesShouldResolveBoundCombo() {
        when(knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        when(indexComboService.resolveBoundCombo(1L)).thenReturn(comboAll());
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(indexVersionDbService.nextVersionNo(5L)).thenReturn("v1");
        when(indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            inv.<WbIndexVersion>getArgument(0).setId(100L);
            return true;
        });
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.<WbPipelineTask>getArgument(0).setId(200L);
            return true;
        });

        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        ComboSnapshot blank = new ComboSnapshot();
        blank.setFileScopeMode("ALL");
        order.setComboSnapshot(blank);

        var result = service.buildCandidate(order);

        assertEquals("v1", result.getVersionNo());
        ArgumentCaptor<WbPipelineTask> taskCaptor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(pipelineTaskDbService).save(taskCaptor.capture());
        assertTrue(taskCaptor.getValue().getStrategySnapshot().contains("chunk-hybrid-v1"),
                taskCaptor.getValue().getStrategySnapshot());
    }

    @Test
    void buildCandidateWithUnknownStageShouldReject40001() {
        // 环节白名单防线：stageStrategies 只允许预处理/切片/向量三环节，未知键=坏数据直接拒绝
        when(knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        ComboSnapshot combo = comboAll();
        combo.getStageStrategies().put(PipelineStage.PARSE.name(), "parse-x-v1");
        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        order.setComboSnapshot(combo);

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.buildCandidate(order));

        assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("未知环节策略"), ex.getMessage());
        verify(indexSetDbService, never()).getOrCreateByKb(anyLong());
    }

    @Test
    void buildCandidateWhenActiveVersionExistsShouldThrow40443() {
        when(knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        WbIndexVersion ready = new WbIndexVersion();
        ready.setId(90L);
        ready.setVersionNo("v1");
        ready.setStatus(IndexVersionStatus.READY.name());
        ready.setComboSnapshot(JsonUtil.toJsonStr(comboAll()));
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of(ready));

        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        order.setComboSnapshot(comboAll());
        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.buildCandidate(order));
        assertEquals(ErrorCode.INDEX_BUILDING_CONFLICT.getCode(), ex.getCode());
        verify(pipelineTaskDbService, never()).save(any());
    }

    @Test
    void publishShouldSwitchPointersAndAudit() {
        WbIndexVersion version = new WbIndexVersion();
        version.setId(20L);
        version.setIndexSetId(5L);
        version.setVersionNo("v2");
        version.setStatus(IndexVersionStatus.READY.name());
        version.setComboSnapshot(JsonUtil.toJsonStr(comboAll()));
        WbIndexVersion old = new WbIndexVersion();
        old.setId(10L);
        old.setVersionNo("v1");
        old.setStatus(IndexVersionStatus.ONLINE.name());
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(1L);

        when(indexVersionDbService.getById(20L)).thenReturn(version);
        when(indexSetDbService.getById(5L)).thenReturn(set);
        when(indexVersionDbService.getById(10L)).thenReturn(old);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(kb);

        service.publish(20L);

        assertEquals(IndexVersionStatus.RETIRED.name(), old.getStatus());
        assertNotNull(old.getRetiredAt());
        assertEquals(IndexVersionStatus.ONLINE.name(), version.getStatus());
        assertNotNull(version.getPublishedAt());
        verify(indexSetDbService).updatePublishedVersion(5L, 20L);
        assertEquals(5L, kb.getPublishedIndexSetId());
        verify(knowledgeBaseDbService).updateById(kb);
        verify(kbAuditLogDbService).saveAudit(AuditActionType.PUBLISH_INDEX, "INDEX_VERSION", 20L, "v1", "v2");
    }

    @Test
    void publishWhenNotReadyShouldThrow40443() {
        WbIndexVersion version = new WbIndexVersion();
        version.setId(20L);
        version.setIndexSetId(5L);
        version.setVersionNo("v2");
        version.setStatus(IndexVersionStatus.BUILDING.name());
        version.setComboSnapshot(JsonUtil.toJsonStr(comboAll()));
        WbIndexSet set = indexSet();
        when(indexVersionDbService.getById(20L)).thenReturn(version);
        when(indexSetDbService.getById(5L)).thenReturn(set);

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.publish(20L));
        assertEquals(ErrorCode.INDEX_BUILDING_CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void rollbackWhenNoDiffShouldSwitchBackAndCompensateBuild() {
        WbIndexVersion target = new WbIndexVersion();
        target.setId(15L);
        target.setIndexSetId(5L);
        target.setVersionNo("v1");
        target.setStatus(IndexVersionStatus.RETIRED.name());
        target.setComboSnapshot(JsonUtil.toJsonStr(comboAll()));
        WbIndexVersion current = new WbIndexVersion();
        current.setId(10L);
        current.setVersionNo("v2");
        current.setStatus(IndexVersionStatus.ONLINE.name());
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);

        when(indexVersionDbService.getById(15L)).thenReturn(target);
        when(indexSetDbService.getById(5L)).thenReturn(set);
        when(indexVersionDbService.getById(10L)).thenReturn(current);
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of(current, target));
        WbFileResult file = file(10L);
        stubCompleteCombo(file);
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(indexVersionDbService.nextVersionNo(5L)).thenReturn("v3");
        when(indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            inv.<WbIndexVersion>getArgument(0).setId(101L);
            return true;
        });
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.<WbPipelineTask>getArgument(0).setId(201L);
            return true;
        });

        service.rollback(15L);

        assertEquals(IndexVersionStatus.RETIRED.name(), current.getStatus());
        assertEquals(IndexVersionStatus.ONLINE.name(), target.getStatus());
        assertNotNull(target.getPublishedAt());
        verify(indexSetDbService).updatePublishedVersion(5L, 15L);
        verify(kbAuditLogDbService).saveAudit(AuditActionType.ROLLBACK_INDEX, "INDEX_VERSION", 15L, "v2", "v1");
        ArgumentCaptor<WbPipelineTask> taskCaptor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(pipelineTaskDbService).save(taskCaptor.capture());
        assertTrue(taskCaptor.getValue().getStrategySnapshot().contains("\"COMPENSATE\""),
                taskCaptor.getValue().getStrategySnapshot());
        verify(taskQueue).enqueue(201L);
        verify(chunkControlService, never()).chunk(anyLong(), anyLong(), any());
    }

    @Test
    void rollbackWhenDiffShouldEnqueueChunkRerun() {
        WbIndexVersion target = new WbIndexVersion();
        target.setId(15L);
        target.setIndexSetId(5L);
        target.setVersionNo("v1");
        target.setStatus(IndexVersionStatus.RETIRED.name());
        target.setComboSnapshot(JsonUtil.toJsonStr(comboAll()));
        WbIndexVersion current = new WbIndexVersion();
        current.setId(10L);
        current.setVersionNo("v2");
        current.setStatus(IndexVersionStatus.ONLINE.name());
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);

        when(indexVersionDbService.getById(15L)).thenReturn(target);
        when(indexSetDbService.getById(5L)).thenReturn(set);
        when(indexVersionDbService.getById(10L)).thenReturn(current);
        // 差异：文件 10 无目标组合任何切片产物（chunkSets 空）→ 拓扑重放从预处理投递
        when(fileResultDbService.listByKb(1L)).thenReturn(List.of(file(10L)));
        when(chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of());
        when(embeddingSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of());
        WbPipelineStrategyVersion strategyRow = new WbPipelineStrategyVersion();
        strategyRow.setId(77L);
        when(strategyVersionDbService.getByTypeAndNameAndVersion("PREPROCESS", "preproc-default", "v1"))
                .thenReturn(strategyRow);

        service.rollback(15L);

        verify(indexSetDbService).updatePublishedVersion(5L, 15L);
        verify(preprocessControlService).preprocess(10L, 77L, null);
        verify(pipelineTaskDbService, never()).save(any());
    }

    @Test
    void currentPublishedShouldReturnNullWhenNoPointer() {
        WbIndexSet set = indexSet();
        when(indexSetDbService.getByKb(1L)).thenReturn(set);
        assertNull(service.currentPublished(1L));
    }

    @Test
    void currentPublishedShouldReturnVersion() {
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);
        WbIndexVersion version = new WbIndexVersion();
        version.setId(10L);
        when(indexSetDbService.getByKb(1L)).thenReturn(set);
        when(indexVersionDbService.getById(10L)).thenReturn(version);
        assertEquals(version, service.currentPublished(1L));
    }

    @Test
    void recycleShouldForbidOnlineVersion() {
        WbIndexVersion version = new WbIndexVersion();
        version.setId(30L);
        version.setIndexSetId(5L);
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(30L);
        when(indexVersionDbService.getById(30L)).thenReturn(version);
        when(indexSetDbService.getById(5L)).thenReturn(set);

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.recycle(30L));
        assertEquals(ErrorCode.INDEX_ONLINE_DELETE_FORBIDDEN.getCode(), ex.getCode());
        verify(milvusIndexPort, never()).drop(anyString());
    }

    @Test
    void recycleShouldDeleteMilvusRowsAndAudit() {
        WbIndexVersion version = new WbIndexVersion();
        version.setId(30L);
        version.setIndexSetId(5L);
        version.setVersionNo("v3");
        version.setStatus(IndexVersionStatus.READY.name());
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);
        when(indexVersionDbService.getById(30L)).thenReturn(version);
        when(indexSetDbService.getById(5L)).thenReturn(set);

        service.recycle(30L);

        verify(milvusIndexPort).drop("kb_1_v3");
        verify(kbAuditLogDbService).saveAudit(AuditActionType.RECYCLE_INDEX, "INDEX_VERSION", 30L, "v3", null);
        verify(indexVersionDbService).removeById(30L);
    }

    @Test
    void rollbackShouldForbidWhenTargetIsCurrent() {
        WbIndexVersion target = new WbIndexVersion();
        target.setId(10L);
        target.setIndexSetId(5L);
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);
        when(indexVersionDbService.getById(10L)).thenReturn(target);
        when(indexSetDbService.getById(5L)).thenReturn(set);

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.rollback(10L));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    private WbIndexVersion versionRow(Long id, String versionNo, String status) {
        WbIndexVersion version = new WbIndexVersion();
        version.setId(id);
        version.setIndexSetId(5L);
        version.setVersionNo(versionNo);
        version.setStatus(status);
        version.setComboSnapshot(JsonUtil.toJsonStr(comboAll()));
        version.setChunkCount(7);
        version.setVectorCount(9);
        return version;
    }

    @Test
    void listVersionsShouldMarkOnlineAndOrder() {
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(20L);
        when(indexSetDbService.getByKb(1L)).thenReturn(set);
        when(indexVersionDbService.listByIndexSetId(5L))
                .thenReturn(List.of(versionRow(20L, "v2", IndexVersionStatus.READY.name()),
                        versionRow(10L, "v1", IndexVersionStatus.RETIRED.name())));

        var versions = service.listVersions(1L);

        assertEquals(2, versions.size());
        assertTrue(versions.getFirst().isOnline());
        assertFalse(versions.get(1).isOnline());
        assertEquals("chunk-hybrid-v1", versions.getFirst().getChunkStrategy());
        assertEquals("embed-default-v1", versions.getFirst().getEmbedStrategy());
        assertEquals("preproc-default-v1", versions.getFirst().getStageStrategies().get("PREPROCESS"));
        assertEquals(7, versions.getFirst().getChunkCount());
    }

    @Test
    void listVersionsShouldReturnEmptyWhenNoSet() {
        when(indexSetDbService.getByKb(1L)).thenReturn(null);
        assertTrue(service.listVersions(1L).isEmpty());
    }

    @Test
    void versionDetailShouldThrow40441WhenNotBelongsToKb() {
        when(indexSetDbService.getByKb(1L)).thenReturn(indexSet());
        WbIndexVersion version = versionRow(30L, "v3", IndexVersionStatus.READY.name());
        version.setIndexSetId(99L);
        when(indexVersionDbService.getById(30L)).thenReturn(version);

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.versionDetail(1L, 30L));
        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND.getCode(), ex.getCode());
    }

    /** selectComboProducts 桩：按（文件#策略）批次行还原真实口径——产物齐 + 切片血统匹配才 complete */
    private void stubComboProductsSelection() {
        when(indexComboReconciler.selectComboProducts(any(ComboSnapshot.class), anyLong(), any(), any()))
                .thenAnswer(inv -> {
                    ComboSnapshot combo = inv.getArgument(0);
                    Long fileId = inv.getArgument(1);
                    Map<String, WbChunkSet> lc = inv.getArgument(2);
                    Map<String, WbEmbeddingSet> le = inv.getArgument(3);
                    WbChunkSet cr = lc.get(fileId + "#" + combo.getChunkStrategy());
                    WbEmbeddingSet er = le.get(fileId + "#" + combo.getEmbedStrategy());
                    if (cr == null || er == null) {
                        return new IndexComboReconciler.ComboProducts(null, null, "缺产物");
                    }
                    String pre = lineageResolver.resolvePreprocessStrategy(cr.getUpstreamProductId());
                    if (!combo.getPreprocessStrategy().equals(pre)) {
                        return new IndexComboReconciler.ComboProducts(null, null, "血统不符");
                    }
                    return new IndexComboReconciler.ComboProducts(cr, er, null);
                });
    }

    private WbChunkSet chunkRowOf(Long id, Long fileId, String chunkStrategy, Long upstreamProductId) {
        WbChunkSet row = new WbChunkSet();
        row.setId(id);
        row.setFileResultId(fileId);
        row.setChunkStrategyVersion(chunkStrategy);
        row.setUpstreamProductId(upstreamProductId);
        return row;
    }

    private WbEmbeddingSet embedRowOf(Long id, Long fileId, String strategyVersion, int recordCount) {
        WbEmbeddingSet row = new WbEmbeddingSet();
        row.setId(id);
        row.setFileResultId(fileId);
        row.setStrategyVersion(strategyVersion);
        row.setRecordCount(recordCount);
        return row;
    }

    @Test
    void listCombosShouldReturnCombosWithMemberFiles() {
        // 文件 10 仅组合 A；文件 20 有 A/B 两套策略历史 → 组合按成员子集返回（非全库交集）
        when(fileResultDbService.listByKb(1L)).thenReturn(List.of(file(10L), file(20L)));
        Map<String, WbChunkSet> latestChunk = new LinkedHashMap<>();
        latestChunk.put("10#chunk-hybrid-v1", chunkRowOf(1L, 10L, "chunk-hybrid-v1", 100L));
        latestChunk.put("20#chunk-hybrid-v1", chunkRowOf(2L, 20L, "chunk-hybrid-v1", 100L));
        latestChunk.put("20#chunk-hybrid-v2", chunkRowOf(3L, 20L, "chunk-hybrid-v2", 101L));
        Map<String, WbEmbeddingSet> latestEmbed = new LinkedHashMap<>();
        latestEmbed.put("10#embed-default-v1", embedRowOf(2L, 10L, "embed-default-v1", 50));
        latestEmbed.put("20#embed-default-v1", embedRowOf(3L, 20L, "embed-default-v1", 30));
        latestEmbed.put("20#embed-default-v2", embedRowOf(4L, 20L, "embed-default-v2", 20));
        Map<String, Set<String>> preprocessByChunk = new LinkedHashMap<>();
        preprocessByChunk.put("chunk-hybrid-v1", new LinkedHashSet<>(List.of("preproc-default-v1")));
        preprocessByChunk.put("chunk-hybrid-v2", new LinkedHashSet<>(List.of("preproc-default-v2")));
        Set<String> embedStrategies = new LinkedHashSet<>(List.of("embed-default-v1", "embed-default-v2"));
        when(indexComboReconciler.catalog(List.of(10L, 20L)))
                .thenReturn(new IndexComboReconciler.ComboCatalog(
                        latestChunk, latestEmbed, preprocessByChunk, embedStrategies));
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        when(lineageResolver.resolvePreprocessStrategy(101L)).thenReturn("preproc-default-v2");
        stubComboProductsSelection();

        var combos = service.listCombos(1L, null);

        assertEquals(4, combos.size());
        IndexComboVO a = combos.stream()
                .filter(c -> "chunk-hybrid-v1".equals(c.getChunkStrategy())
                        && "embed-default-v1".equals(c.getEmbedStrategy()))
                .findFirst().orElseThrow();
        assertEquals(List.of(10L, 20L), a.getFileResultIds());
        assertEquals(2, a.getFileCount());
        assertEquals(80, a.getVectorCount());
        assertTrue(combos.stream().noneMatch(c -> c.getFileResultIds().isEmpty()));
    }

    @Test
    void listCombosShouldScopeMembersToGivenFiles() {
        // 范围收窄：仅文件 10 参与成员判定
        Map<String, WbChunkSet> latestChunk = new LinkedHashMap<>();
        latestChunk.put("10#chunk-hybrid-v1", chunkRowOf(1L, 10L, "chunk-hybrid-v1", 100L));
        Map<String, WbEmbeddingSet> latestEmbed = new LinkedHashMap<>();
        latestEmbed.put("10#embed-default-v1", embedRowOf(2L, 10L, "embed-default-v1", 50));
        Map<String, Set<String>> preprocessByChunk = new LinkedHashMap<>();
        preprocessByChunk.put("chunk-hybrid-v1", new LinkedHashSet<>(List.of("preproc-default-v1")));
        Set<String> embedStrategies = new LinkedHashSet<>(List.of("embed-default-v1"));
        when(indexComboReconciler.catalog(List.of(10L)))
                .thenReturn(new IndexComboReconciler.ComboCatalog(
                        latestChunk, latestEmbed, preprocessByChunk, embedStrategies));
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        stubComboProductsSelection();

        var combos = service.listCombos(1L, List.of(10L));

        assertEquals(1, combos.size());
        assertEquals(List.of(10L), combos.getFirst().getFileResultIds());
        assertEquals(1, combos.getFirst().getFileCount());
        assertEquals(50, combos.getFirst().getVectorCount());
    }

    @Test
    void validateShouldPassAllChecks() {
        WbIndexVersion version = versionRow(20L, "v2", IndexVersionStatus.READY.name());
        when(indexVersionDbService.getById(20L)).thenReturn(version);
        when(indexSetDbService.getById(5L)).thenReturn(indexSet());
        when(indexComboReconciler.computeExpected(eq(1L), any(ComboSnapshot.class)))
                .thenReturn(new IndexComboReconciler.ComboExpectation(Set.of("chunk-1"), 1, 1024,
                        true, null, true, null, List.of(1.0f, 2.0f), "投标保证金"));
        when(milvusIndexPort.listChunkIds("kb_1_v2")).thenReturn(List.of("chunk-1"));
        when(milvusIndexPort.searchVector(anyString(), any(VectorQuery.class)))
                .thenReturn(List.of(VectorHit.builder().chunkId("chunk-1").build()));
        when(milvusIndexPort.searchFullText(anyString(), any(FullTextQuery.class)))
                .thenReturn(List.of(FullTextHit.builder().chunkId("chunk-1").build()));

        var result = service.validate(20L);

        assertTrue(result.isPassed());
        assertEquals(3, result.getItems().size());
        assertTrue(result.getItems().getFirst().isPassed());
    }

    @Test
    void validateShouldFailWhenSmokeMisses() {
        WbIndexVersion version = versionRow(20L, "v2", IndexVersionStatus.READY.name());
        when(indexVersionDbService.getById(20L)).thenReturn(version);
        when(indexSetDbService.getById(5L)).thenReturn(indexSet());
        when(indexComboReconciler.computeExpected(eq(1L), any(ComboSnapshot.class)))
                .thenReturn(new IndexComboReconciler.ComboExpectation(Set.of("chunk-1"), 1, 1024,
                        true, null, true, null, List.of(1.0f, 2.0f), "投标保证金"));
        when(milvusIndexPort.listChunkIds("kb_1_v2")).thenReturn(List.of("chunk-1"));
        when(milvusIndexPort.searchVector(anyString(), any(VectorQuery.class))).thenReturn(List.of());
        when(milvusIndexPort.searchFullText(anyString(), any(FullTextQuery.class))).thenReturn(List.of());

        var result = service.validate(20L);

        assertFalse(result.isPassed());
        assertFalse(result.getItems().get(1).isPassed());
        assertFalse(result.getItems().get(2).isPassed());
    }

    @Test
    void validateShouldReportIncompleteCombo() {
        WbIndexVersion version = versionRow(20L, "v2", IndexVersionStatus.READY.name());
        when(indexVersionDbService.getById(20L)).thenReturn(version);
        when(indexSetDbService.getById(5L)).thenReturn(indexSet());
        when(indexComboReconciler.computeExpected(eq(1L), any(ComboSnapshot.class)))
                .thenReturn(new IndexComboReconciler.ComboExpectation(Set.of(), 0, 0, false,
                        "文件 10 切片血统与组合预处理策略不符", true, null, null, null));

        var result = service.validate(20L);

        assertFalse(result.isPassed());
        assertFalse(result.getItems().getFirst().isPassed());
        assertTrue(result.getItems().getFirst().getDetail().contains("组合产物不完整"),
                result.getItems().getFirst().getDetail());
        verify(milvusIndexPort, never()).searchVector(anyString(), any());
        verify(milvusIndexPort, never()).searchFullText(anyString(), any());
    }

    @Test
    void validateShouldForbidNotReadyVersion() {
        WbIndexVersion version = versionRow(20L, "v2", IndexVersionStatus.BUILDING.name());
        when(indexVersionDbService.getById(20L)).thenReturn(version);
        when(indexSetDbService.getById(5L)).thenReturn(indexSet());

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.validate(20L));
        assertEquals(ErrorCode.INDEX_BUILDING_CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void onFileProductsReadyWhenOnlineComboIsLegacyShouldFailWithExplicitError() {
        // 在线版本为缺少 stageStrategies 的快照 → 快照边界断言拒绝（40448），不 NPE、不静默跳过
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(1L);
        kb.setStrategyBindingEnabled(1);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(kb);
        WbEmbeddingSet embedRow = new WbEmbeddingSet();
        embedRow.setId(2L);
        embedRow.setFileResultId(10L);
        embedRow.setChunkSetRef(1L);
        embedRow.setStrategyVersion("embed-default-v1");
        embedRow.setDimension(1024);
        when(embeddingSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(embedRow));
        WbChunkSet chunkRow = new WbChunkSet();
        chunkRow.setId(1L);
        chunkRow.setFileResultId(10L);
        chunkRow.setChunkStrategyVersion("chunk-hybrid-v1");
        chunkRow.setUpstreamProductId(100L);
        when(chunkSetDbService.getById(1L)).thenReturn(chunkRow);
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        when(indexRowAssembler.assemble(eq(10L), eq("userA"), eq(chunkRow), eq(embedRow)))
                .thenReturn(List.of(indexRow()));
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(indexVersionDbService.nextVersionNo(5L)).thenReturn("v2");
        when(indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            inv.<WbIndexVersion>getArgument(0).setId(100L);
            return true;
        });
        when(milvusIndexPort.listChunkIds("kb_1_v2", 10L)).thenReturn(List.of("chunk-1"));
        // 在线版本 = 缺少 stageStrategies 的快照
        WbIndexSet onlineSet = indexSet();
        onlineSet.setCurrentPublishedVersionId(50L);
        when(indexSetDbService.getByKb(1L)).thenReturn(onlineSet);
        WbIndexVersion onlineVersion = new WbIndexVersion();
        onlineVersion.setId(50L);
        onlineVersion.setVersionNo("v1");
        onlineVersion.setComboSnapshot("{\"fileScopeMode\":\"ALL\",\"shape\":\"FULL_VECTOR\"}");
        when(indexVersionDbService.getById(50L)).thenReturn(onlineVersion);
        when(indexComboService.resolveBoundCombo(1L)).thenReturn(comboAll());
        // 自动发布路径与本用例无关：save 后 versionId=100 按未找到处理（异常由 maybeAutoPublish 兜底）
        lenient().when(indexVersionDbService.getById(100L)).thenReturn(null);

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.onFileProductsReady(10L));

        assertEquals(ErrorCode.INDEX_COMBO_SNAPSHOT_LEGACY.getCode(), ex.getCode());
        // 本文件已按产物组合追加（幂等，重灌后可重放），失败点恰在拓扑重放
        verify(milvusIndexPort).append(eq("kb_1_v2"), anyList());
        verify(preprocessControlService, never()).preprocess(anyLong(), anyLong(), any());
    }

    @Test
    void rollbackWhenTargetComboIsLegacyShouldFailWithExplicitError() {
        WbIndexVersion target = new WbIndexVersion();
        target.setId(15L);
        target.setIndexSetId(5L);
        target.setVersionNo("v1");
        target.setStatus(IndexVersionStatus.RETIRED.name());
        target.setComboSnapshot("{\"fileScopeMode\":\"ALL\",\"shape\":\"FULL_VECTOR\"}");
        when(indexVersionDbService.getById(15L)).thenReturn(target);
        when(indexSetDbService.getById(5L)).thenReturn(indexSet());

        // 快照边界断言显式拒绝（40448）；生产侧 @Transactional 使指针切换一并回滚（单测无法模拟事务）
        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.rollback(15L));

        assertEquals(ErrorCode.INDEX_COMBO_SNAPSHOT_LEGACY.getCode(), ex.getCode());
        verify(indexSetDbService, never()).updatePublishedVersion(anyLong(), anyLong());
        verify(chunkControlService, never()).chunk(anyLong(), anyLong(), any());
        verify(preprocessControlService, never()).preprocess(anyLong(), anyLong(), any());
    }

    private ComboSnapshot comboList(List<Long> fileIds, String embedStrategy) {
        ComboSnapshot combo = ComboSnapshot.of("preproc-default-v1", "chunk-hybrid-v1", embedStrategy);
        combo.setFileScopeMode("LIST");
        combo.setFileResultIds(fileIds);
        return combo;
    }

    private WbIndexVersion listVersion(String versionNo, String status, ComboSnapshot combo) {
        WbIndexVersion version = new WbIndexVersion();
        version.setId(900L);
        version.setIndexSetId(5L);
        version.setVersionNo(versionNo);
        version.setStatus(status);
        version.setComboSnapshot(JsonUtil.toJsonStr(combo));
        return version;
    }

    @Test
    void onFileProductsReadyShouldAppendToMatchingFrozenScopesOnly() {
        // 评测模式（绑定关）不自动注册 ALL 组合；范围内三元组匹配的 LIST 版本照常追加
        //（范围外/三元组不同不进）
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(1L);
        kb.setStrategyBindingEnabled(0);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(kb);
        WbEmbeddingSet embedRow = new WbEmbeddingSet();
        embedRow.setId(2L);
        embedRow.setFileResultId(10L);
        embedRow.setChunkSetRef(1L);
        embedRow.setStrategyVersion("embed-default-v1");
        embedRow.setDimension(1024);
        when(embeddingSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(embedRow));
        WbChunkSet chunkRow = new WbChunkSet();
        chunkRow.setId(1L);
        chunkRow.setFileResultId(10L);
        chunkRow.setChunkStrategyVersion("chunk-hybrid-v1");
        chunkRow.setUpstreamProductId(100L);
        when(chunkSetDbService.getById(1L)).thenReturn(chunkRow);
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        when(indexRowAssembler.assemble(eq(10L), eq("userA"), eq(chunkRow), eq(embedRow)))
                .thenReturn(List.of(indexRow()));
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(indexSetDbService.getByKb(1L)).thenReturn(indexSet());
        // 三个 LIST 版本：v9 范围内+三元组匹配；v8 范围不含该文件；v7 三元组不同
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of(
                listVersion("v9", IndexVersionStatus.READY.name(), comboList(List.of(10L), "embed-default-v1")),
                listVersion("v8", IndexVersionStatus.READY.name(), comboList(List.of(11L), "embed-default-v1")),
                listVersion("v7", IndexVersionStatus.READY.name(), comboList(List.of(10L), "embed-other-v2"))));

        service.onFileProductsReady(10L);

        // 评测模式：不注册 ALL 组合（无 kb_1_v1），仅冻结集追加
        verify(indexVersionDbService, never()).save(any(WbIndexVersion.class));
        verify(milvusIndexPort, never()).ensureCollection(anyString(), anyInt());
        verify(milvusIndexPort, never()).append(eq("kb_1_v1"), anyList());
        verify(milvusIndexPort).append(eq("kb_1_v9"), anyList());
        verify(milvusIndexPort, never()).append(eq("kb_1_v8"), anyList());
        verify(milvusIndexPort, never()).append(eq("kb_1_v7"), anyList());
    }

    @Test
    void publishShouldRejectFrozenScopeVersion() {
        WbIndexVersion version = new WbIndexVersion();
        version.setId(20L);
        version.setVersionNo("v9");
        version.setComboSnapshot(JsonUtil.toJsonStr(comboList(List.of(10L), "embed-default-v1")));
        when(indexVersionDbService.getById(20L)).thenReturn(version);

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.publish(20L));

        assertEquals(ErrorCode.INDEX_FROZEN_SCOPE_PUBLISH_FORBIDDEN.getCode(), ex.getCode());
        verify(indexSetDbService, never()).updatePublishedVersion(anyLong(), anyLong());
    }

    @Test
    void rollbackShouldRejectFrozenScopeTarget() {
        WbIndexVersion target = new WbIndexVersion();
        target.setId(15L);
        target.setIndexSetId(5L);
        target.setVersionNo("v9");
        target.setStatus(IndexVersionStatus.READY.name());
        target.setComboSnapshot(JsonUtil.toJsonStr(comboList(List.of(10L), "embed-default-v1")));
        when(indexVersionDbService.getById(15L)).thenReturn(target);
        when(indexSetDbService.getById(5L)).thenReturn(indexSet());

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.rollback(15L));

        assertEquals(ErrorCode.INDEX_FROZEN_SCOPE_PUBLISH_FORBIDDEN.getCode(), ex.getCode());
        verify(indexSetDbService, never()).updatePublishedVersion(anyLong(), anyLong());
        verify(kbAuditLogDbService, never()).saveAudit(any(), any(), anyLong(), any(), any());
    }

    @Test
    void buildCandidateShouldRejectListScopeWithoutFiles() {
        when(knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        ComboSnapshot combo = comboAll();
        combo.setFileScopeMode("LIST");
        order.setComboSnapshot(combo);

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.buildCandidate(order));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void buildCandidateShouldRejectForeignFilesInListScope() {
        when(knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        when(fileResultDbService.listByKb(1L)).thenReturn(List.of(file(9L)));
        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        ComboSnapshot combo = comboAll();
        combo.setFileScopeMode("LIST");
        combo.setFileResultIds(List.of(10L));
        order.setComboSnapshot(combo);

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.buildCandidate(order));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("非本知识库"), ex.getMessage());
    }

    @Test
    void buildCandidateShouldNormalizeAllScopeRange() {
        // ALL 范围归一化——残留 fileResultIds 置空，落库快照干净
        when(knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(indexVersionDbService.nextVersionNo(5L)).thenReturn("v1");
        AtomicReference<WbIndexVersion> saved = new AtomicReference<>();
        when(indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            WbIndexVersion v = inv.getArgument(0);
            saved.set(v);
            return true;
        });
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.<WbPipelineTask>getArgument(0).setId(200L);
            return true;
        });
        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        ComboSnapshot combo = comboAll();
        combo.setFileResultIds(List.of(10L));
        order.setComboSnapshot(combo);
        order.setTrigger(IndexBuildTrigger.REBUILD.name());

        service.buildCandidate(order);

        ComboSnapshot savedCombo = JsonUtil.toObject(saved.get().getComboSnapshot(), ComboSnapshot.class);
        assertNotNull(savedCombo);
        assertEquals("ALL", savedCombo.getFileScopeMode());
        assertNull(savedCombo.getFileResultIds());
    }
}
