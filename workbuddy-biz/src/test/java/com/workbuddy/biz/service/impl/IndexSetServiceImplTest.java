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
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.dto.response.index.IndexComboVO;
import com.workbuddy.common.dto.response.index.IndexVersionVO;
import com.workbuddy.common.enums.index.IndexBuildTrigger;
import com.workbuddy.common.enums.index.IndexShape;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
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

import java.time.LocalDateTime;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    /**
     * 产物就绪（绑定开）：登记组合、追加、对账并自动发布。
     *
     * <p>守护 {@code onFileProductsReady}：漏追加或漏发布即失败。
     */
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
        // 追加后转 READY 时必须记下校验时间（前端「最近校验」依赖它）
        assertNotNull(savedVersion.get().getValidatedAt());
        verify(indexSetDbService).updatePublishedVersion(5L, 100L);
        verify(kbAuditLogDbService).saveAudit(AuditActionType.PUBLISH_INDEX, "INDEX_VERSION", 100L, null, "v1");
    }

    /**
     * 产物就绪（绑定关）：评测模式不自动登记组合。
     *
     * <p>守护 {@code onFileProductsReady}：测评库被自动建集合即失败。
     */
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

    /**
     * 产物就绪（绑定关）：已有活跃版本时照常追加。
     *
     * <p>守护 {@code onFileProductsReady}：有集合不追加即失败。
     */
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

    /**
     * 产物组合 ≠ 在线组合：按在线组合拓扑补齐。
     *
     * <p>守护 {@code onFileProductsReady}：文件被丢下即失败。
     */
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

    /**
     * 手动构建：注册组合不要求产物齐，任务入队。
     *
     * <p>守护 {@code buildCandidate}：要求产物齐会挡住切策略。
     */
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

    /**
     * 手动构建：策略不全时用知识库绑定组合补齐。
     *
     * <p>守护 {@code buildCandidate}：空策略组合被构建即失败。
     */
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

    /**
     * 手动构建：未知环节策略按 40001 拒绝。
     *
     * <p>守护 {@code buildCandidate}：坏数据被携带即失败。
     */
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

    /**
     * 手动构建：同组合活跃版本冲突 40443。
     *
     * <p>守护 {@code buildCandidate}：重复建版本即失败。
     */
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

    /**
     * 发布：旧版退役、指针切换、知识库回填、PUBLISH 审计。
     *
     * <p>守护 {@code publish}：漏退役或漏审计即失败。
     */
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

    /**
     * 发布：非就绪版本拒绝（40443）。
     *
     * <p>守护 {@code publish}：未就绪上线即失败。
     */
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

    /**
     * 回退（无差异）：指针切回并投递 COMPENSATE 构建。
     *
     * <p>守护 {@code rollback}：漏补偿构建即失败。
     */
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
        // 同组合的 READY 活跃版本：COMPENSATE 允许重开（只有 CREATED/BUILDING 才跳过）
        WbIndexVersion readySameCombo = new WbIndexVersion();
        readySameCombo.setId(30L);
        readySameCombo.setStatus(IndexVersionStatus.READY.name());
        readySameCombo.setComboSnapshot(JsonUtil.toJsonStr(comboAll()));
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of(current, target, readySameCombo));
        WbFileResult file = file(10L);
        stubCompleteCombo(file);
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        // 版本号并发冲突：第一次 v3 撞唯一键，重取后 v4
        when(indexVersionDbService.nextVersionNo(5L)).thenReturn("v3", "v4");
        when(indexVersionDbService.save(any(WbIndexVersion.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_set_version"))
                .thenAnswer(inv -> {
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
        ArgumentCaptor<WbIndexVersion> versionCaptor = ArgumentCaptor.forClass(WbIndexVersion.class);
        verify(indexVersionDbService, times(2)).save(versionCaptor.capture());
        // 冲突重取后必须用新版本号再落一次（沿用旧号会二次撞键）
        assertEquals("v4", versionCaptor.getValue().getVersionNo());
        ArgumentCaptor<WbPipelineTask> taskCaptor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(pipelineTaskDbService).save(taskCaptor.capture());
        assertTrue(taskCaptor.getValue().getStrategySnapshot().contains("\"COMPENSATE\""),
                taskCaptor.getValue().getStrategySnapshot());
        verify(taskQueue).enqueue(201L);
        verify(chunkControlService, never()).chunk(anyLong(), anyLong(), any());
    }

    /**
     * 回退（有差异）：从缺失的最上游环节重跑。
     *
     * <p>守护 {@code rollback}：差异文件不补即失败。
     */
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

    /**
     * 当前在线版本：无指针返回 null。
     *
     * <p>守护 {@code currentPublished}：空指针即失败。
     */
    @Test
    void currentPublishedShouldReturnNullWhenNoPointer() {
        WbIndexSet set = indexSet();
        when(indexSetDbService.getByKb(1L)).thenReturn(set);
        assertNull(service.currentPublished(1L));
    }

    /**
     * 当前在线版本：按指针取版本行。
     *
     * <p>守护 {@code currentPublished}：取错行即失败。
     */
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

    /**
     * 回收：在线版本禁止删除（40446）。
     *
     * <p>守护 {@code recycle}：在线集合被删即失败。
     */
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

    /**
     * 回收：先删集合再删版本行并写 RECYCLE 审计。
     *
     * <p>守护 {@code recycle}：删行不删集合即失败。
     */
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

    /**
     * 回退：目标即当前在线版本按 40001 拒绝。
     *
     * <p>守护 {@code rollback}：原地回退即失败。
     */
    @Test
    void rollbackShouldForbidWhenTargetIsCurrent() {
        WbIndexVersion target = new WbIndexVersion();
        target.setId(10L);
        target.setIndexSetId(5L);
        target.setStatus(IndexVersionStatus.ONLINE.name());
        target.setComboSnapshot(JsonUtil.toJsonStr(comboAll()));
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

    /**
     * 版本列表：在线标记与组合快照展开。
     *
     * <p>守护 {@code listVersions}：在线标记或组合字段错即失败。
     */
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

    /**
     * 版本列表：无集合返回空列表。
     *
     * <p>守护 {@code listVersions}：空集合查库即失败。
     */
    @Test
    void listVersionsShouldReturnEmptyWhenNoSet() {
        when(indexSetDbService.getByKb(1L)).thenReturn(null);
        assertTrue(service.listVersions(1L).isEmpty());
    }

    /**
     * 版本详情：版本不属于该库按 40441。
     *
     * <p>守护 {@code versionDetail}：跨库可读即失败。
     */
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

    /**
     * 组合列表：按成员子集返回组合与文件/向量数预览。
     *
     * <p>守护 {@code listCombos}：按全库交集返回即失败。
     */
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
        assertEquals("ALL", a.getFileScopeMode());
        assertEquals(com.workbuddy.common.enums.index.IndexShape.FULL_VECTOR, a.getShape());
        assertEquals("preproc-default-v1", a.getStageStrategies().get("PREPROCESS"));
        assertTrue(a.isComplete());
        assertEquals(List.of(10L, 20L), a.getFileResultIds());
        assertEquals(2, a.getFileCount());
        assertEquals(80, a.getVectorCount());
        assertTrue(combos.stream().noneMatch(c -> c.getFileResultIds().isEmpty()));
    }

    /**
     * 组合列表：范围收窄到指定文件。
     *
     * <p>守护 {@code listCombos}：范围外的文件混入即失败。
     */
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

    /**
     * 校验：对账一致且冒烟命中时通过三项。
     *
     * <p>守护 {@code validate}：对账或冒烟判错即失败。
     */
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

    /**
     * 校验：冒烟未命中判失败。
     *
     * <p>守护 {@code validate}：空命中判通过即失败。
     */
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

    /**
     * 校验：组合产物不完整时陈述缺口且不做冒烟。
     *
     * <p>守护 {@code validate}：缺产物仍冒烟即失败。
     */
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
        // 必须陈述具体缺口（而不是把 dimError 的 null 拼进去）
        assertTrue(result.getItems().getFirst().getDetail().contains("文件 10 切片血统与组合预处理策略不符"),
                result.getItems().getFirst().getDetail());
        verify(milvusIndexPort, never()).searchVector(anyString(), any());
        verify(milvusIndexPort, never()).searchFullText(anyString(), any());
    }

    /**
     * 校验：构建中版本拒绝（40443）。
     *
     * <p>守护 {@code validate}：构建中可校验即失败。
     */
    @Test
    void validateShouldForbidNotReadyVersion() {
        WbIndexVersion version = versionRow(20L, "v2", IndexVersionStatus.BUILDING.name());
        when(indexVersionDbService.getById(20L)).thenReturn(version);
        when(indexSetDbService.getById(5L)).thenReturn(indexSet());

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.validate(20L));
        assertEquals(ErrorCode.INDEX_BUILDING_CONFLICT.getCode(), ex.getCode());
    }

    /**
     * 产物就绪：在线版本快照缺环节维度按 40448 拒绝。
     *
     * <p>守护 {@code onFileProductsReady}：静默跳过或空指针即失败。
     */
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

    /**
     * 回退：目标版本快照缺环节维度按 40448 拒绝。
     *
     * <p>守护 {@code rollback}：静默降级即失败。
     */
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

    /**
     * 产物就绪：只追加到范围包含该文件的冻结集版本。
     *
     * <p>守护 {@code onFileProductsReady}：范围外文件混进冻结集即失败。
     */
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

    /**
     * 发布：冻结集版本禁止发布（40449）。
     *
     * <p>守护 {@code publish}：评测集合上线即失败。
     */
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

    /**
     * 回退：冻结集版本禁止回退（40449）。
     *
     * <p>守护 {@code rollback}：评测集合切回即失败。
     */
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

    /**
     * 手动构建：LIST 范围缺文件列表按 40001 拒绝。
     *
     * <p>守护 {@code buildCandidate}：空列表被放行即失败。
     */
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

    /**
     * 手动构建：文件列表含外库文件按 40001 拒绝。
     *
     * <p>守护 {@code buildCandidate}：外库文件混入即失败。
     */
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

    /**
     * 手动构建：ALL 范围归一化，残留文件列表置空。
     *
     * <p>守护 {@code buildCandidate}：脏范围写进快照即失败。
     */
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

    /**
     * ALL 范围且范围字段为 null 时也归一化成 ALL。
     *
     * <p>守护 {@code buildCandidate} 的范围归一：残留 null 会写进组合快照，影响组合判等。
     */
    @Test
    void buildCandidateShouldFillNullScopeMode() {
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
        combo.setFileScopeMode(null);
        combo.setFileResultIds(List.of(10L));
        order.setComboSnapshot(combo);

        service.buildCandidate(order);

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
        KnowledgeException nullOrder = assertThrows(KnowledgeException.class,
                () -> service.buildCandidate(null));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), nullOrder.getCode());

        KnowledgeException noKb = assertThrows(KnowledgeException.class,
                () -> service.buildCandidate(new BuildOrder()));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), noKb.getCode());

        BuildOrder noCombo = new BuildOrder();
        noCombo.setKnowledgeBaseId(1L);
        KnowledgeException comboMissing = assertThrows(KnowledgeException.class,
                () -> service.buildCandidate(noCombo));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), comboMissing.getCode());

        when(knowledgeBaseDbService.getById(1L)).thenReturn(null);
        BuildOrder unknownKb = new BuildOrder();
        unknownKb.setKnowledgeBaseId(1L);
        unknownKb.setComboSnapshot(comboAll());
        KnowledgeException notFound = assertThrows(KnowledgeException.class,
                () -> service.buildCandidate(unknownKb));
        assertEquals(ErrorCode.KB_NOT_FOUND.getCode(), notFound.getCode());
        verify(pipelineTaskDbService, never()).save(any());
    }

    /**
     * 环节策略不全且知识库没有绑定组合时按 40445 拒绝。
     *
     * <p>守护 {@code buildCandidate} 的绑定兜底：拿不到绑定组合还能继续会构建出空策略组合。
     */
    @Test
    void buildCandidateWithoutStrategiesOrBindingShouldReject() {
        when(knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        when(indexComboService.resolveBoundCombo(1L)).thenReturn(null);
        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        ComboSnapshot blank = new ComboSnapshot();
        order.setComboSnapshot(blank);

        KnowledgeException ex = assertThrows(KnowledgeException.class, () -> service.buildCandidate(order));

        assertEquals(ErrorCode.INDEX_COMBO_INCOMPLETE.getCode(), ex.getCode());
        verify(pipelineTaskDbService, never()).save(any());
    }

    /**
     * LIST 范围且文件全属本库：不触发「非本库文件」拒绝。
     *
     * <p>守护 {@code buildCandidate} 的归属过滤：过滤条件写反会把本库文件当外库。
     */
    @Test
    void buildCandidateWithOwnFilesInListScopeShouldAccept() {
        when(knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        when(fileResultDbService.listByKb(1L)).thenReturn(List.of(file(10L), file(20L)));
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
        ComboSnapshot combo = comboAll();
        combo.setFileScopeMode("LIST");
        combo.setFileResultIds(List.of(10L, 20L));
        order.setComboSnapshot(combo);

        var result = service.buildCandidate(order);

        assertEquals("v1", result.getVersionNo());
        verify(taskQueue).enqueue(200L);
    }

    /**
     * 建版本行与 BUILD_INDEX 任务：两行的关键列逐列落库，命令快照可回读。
     *
     * <p>守护 {@code createVersionAndTask} 的字段搬运：任务缺 stage/status 就永远不会被消费。
     */
    @Test
    void createVersionAndTaskShouldPersistVersionAndTaskFields() {
        when(knowledgeBaseDbService.getById(1L)).thenReturn(new WbKnowledgeBase());
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(indexVersionDbService.nextVersionNo(5L)).thenReturn("v4");
        AtomicReference<WbIndexVersion> saved = new AtomicReference<>();
        when(indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            WbIndexVersion v = inv.getArgument(0);
            v.setId(104L);
            saved.set(v);
            return true;
        });
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.<WbPipelineTask>getArgument(0).setId(204L);
            return true;
        });
        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(1L);
        order.setComboSnapshot(comboAll());

        var result = service.buildCandidate(order);

        ArgumentCaptor<WbPipelineTask> taskCaptor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(pipelineTaskDbService).save(taskCaptor.capture());
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
        verify(taskQueue).enqueue(204L);
    }

    /**
     * 版本详情把版本行的每个字段都带到 VO（含组合快照展开与在线标记）。
     *
     * <p>守护 {@code toVersionVO}：漏搬 buildError/taskId/时间戳等任何一列都会让前端看到空白。
     */
    @Test
    void versionDetailShouldMapEveryVersionField() {
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(20L);
        when(indexSetDbService.getByKb(1L)).thenReturn(set);
        WbIndexVersion version = versionRow(20L, "v2", IndexVersionStatus.ONLINE.name());
        ComboSnapshot combo = comboAll();
        combo.setFileScopeMode("LIST");
        combo.setFileResultIds(List.of(10L, 20L));
        version.setComboSnapshot(JsonUtil.toJsonStr(combo));
        version.setBuildError("历史失败原因");
        version.setTaskId(200L);
        LocalDateTime at = LocalDateTime.of(2024, 5, 1, 10, 0);
        version.setValidatedAt(at);
        version.setPublishedAt(at.plusHours(1));
        version.setPublishedBy("admin");
        version.setRetiredAt(at.plusHours(2));
        version.setRetiredBy("ops");
        version.setCreateTime(at.minusDays(1));
        when(indexVersionDbService.getById(20L)).thenReturn(version);

        IndexVersionVO vo = service.versionDetail(1L, 20L);

        assertEquals(20L, vo.getId());
        assertEquals("v2", vo.getVersionNo());
        assertEquals("LIST", vo.getFileScopeMode());
        assertEquals(List.of(10L, 20L), vo.getFileResultIds());
        assertEquals(IndexShape.FULL_VECTOR, vo.getShape());
        assertEquals("chunk-hybrid-v1", vo.getChunkStrategy());
        assertEquals("embed-default-v1", vo.getEmbedStrategy());
        assertEquals("preproc-default-v1", vo.getStageStrategies().get(PipelineStage.PREPROCESS.name()));
        assertEquals(7, vo.getChunkCount());
        assertEquals(9, vo.getVectorCount());
        assertEquals(IndexVersionStatus.ONLINE.name(), vo.getStatus());
        assertEquals("历史失败原因", vo.getBuildError());
        assertEquals(200L, vo.getTaskId());
        assertEquals(at, vo.getValidatedAt());
        assertEquals(at.plusHours(1), vo.getPublishedAt());
        assertEquals("admin", vo.getPublishedBy());
        assertEquals(at.plusHours(2), vo.getRetiredAt());
        assertEquals("ops", vo.getRetiredBy());
        assertEquals(at.minusDays(1), vo.getCreateTime());
        assertTrue(vo.isOnline());
    }

    /**
     * 版本详情：集合不存在、版本不存在都按 40441 处理。
     *
     * <p>守护 {@code versionDetail} 的两段存在性校验：缺任一段都会 NPE 或泄漏半截数据。
     */
    @Test
    void versionDetailShouldRequireSetAndVersion() {
        when(indexSetDbService.getByKb(1L)).thenReturn(null);
        // 版本行存在但集合缺失：集合校验必须是唯一拒绝点（否则会空指针）
        when(indexVersionDbService.getById(20L)).thenReturn(versionRow(20L, "v2", IndexVersionStatus.READY.name()));
        KnowledgeException noSet = assertThrows(KnowledgeException.class,
                () -> service.versionDetail(1L, 20L));
        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND.getCode(), noSet.getCode());

        when(indexSetDbService.getByKb(1L)).thenReturn(indexSet());
        when(indexVersionDbService.getById(20L)).thenReturn(null);
        KnowledgeException noVersion = assertThrows(KnowledgeException.class,
                () -> service.versionDetail(1L, 20L));
        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND.getCode(), noVersion.getCode());
    }

    /**
     * 发布：版本不存在与集合不存在都按 40441 拒绝，且不切换指针。
     *
     * <p>守护 {@code requireVersion}/{@code requireSetOf} 的两道兜底。
     */
    @Test
    void publishShouldRequireVersionAndSet() {
        when(indexVersionDbService.getById(20L)).thenReturn(null);
        KnowledgeException noVersion = assertThrows(KnowledgeException.class, () -> service.publish(20L));
        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND.getCode(), noVersion.getCode());

        WbIndexVersion version = versionRow(20L, "v2", IndexVersionStatus.READY.name());
        when(indexVersionDbService.getById(20L)).thenReturn(version);
        when(indexSetDbService.getById(5L)).thenReturn(null);
        KnowledgeException noSet = assertThrows(KnowledgeException.class, () -> service.publish(20L));
        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND.getCode(), noSet.getCode());
        verify(indexSetDbService, never()).updatePublishedVersion(anyLong(), anyLong());
    }

    /**
     * 回退：目标/集合缺失 40441、当前无在线版本 40443、目标构建中 40443。
     *
     * <p>守护 {@code rollback} 的四道前置校验：任何一道放行都会切到不可用版本。
     */
    @Test
    void rollbackShouldGuardTargetSetPointerAndBuildingStatus() {
        when(indexVersionDbService.getById(15L)).thenReturn(null);
        KnowledgeException noTarget = assertThrows(KnowledgeException.class, () -> service.rollback(15L));
        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND.getCode(), noTarget.getCode());

        WbIndexVersion target = versionRow(15L, "v1", IndexVersionStatus.RETIRED.name());
        when(indexVersionDbService.getById(15L)).thenReturn(target);
        when(indexSetDbService.getById(5L)).thenReturn(null);
        KnowledgeException noSet = assertThrows(KnowledgeException.class, () -> service.rollback(15L));
        assertEquals(ErrorCode.INDEX_VERSION_NOT_FOUND.getCode(), noSet.getCode());

        WbIndexSet noPointer = indexSet();
        when(indexSetDbService.getById(5L)).thenReturn(noPointer);
        KnowledgeException notPublished = assertThrows(KnowledgeException.class, () -> service.rollback(15L));
        assertEquals(ErrorCode.INDEX_NOT_PUBLISHED.getCode(), notPublished.getCode());

        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);
        when(indexSetDbService.getById(5L)).thenReturn(set);
        WbIndexVersion building = versionRow(15L, "v1", IndexVersionStatus.BUILDING.name());
        when(indexVersionDbService.getById(15L)).thenReturn(building);
        KnowledgeException conflict = assertThrows(KnowledgeException.class, () -> service.rollback(15L));
        assertEquals(ErrorCode.INDEX_BUILDING_CONFLICT.getCode(), conflict.getCode());
        verify(indexSetDbService, never()).updatePublishedVersion(anyLong(), anyLong());
    }

    /**
     * 回退复活目标版本时要清掉退役留痕。
     *
     * <p>守护 {@code rollback} 的复活清场：留着 retiredAt/retiredBy 等于版本既在线又已退役。
     */
    @Test
    void rollbackShouldClearRetiredTraceOnTarget() {
        WbIndexVersion target = versionRow(15L, "v1", IndexVersionStatus.RETIRED.name());
        target.setRetiredAt(LocalDateTime.of(2024, 1, 1, 0, 0));
        target.setRetiredBy("ops");
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);
        WbIndexVersion current = versionRow(10L, "v2", IndexVersionStatus.ONLINE.name());
        when(indexVersionDbService.getById(15L)).thenReturn(target);
        when(indexVersionDbService.getById(10L)).thenReturn(current);
        when(indexSetDbService.getById(5L)).thenReturn(set);
        when(fileResultDbService.listByKb(1L)).thenReturn(List.of(file(10L)));
        stubCompleteCombo(file(10L));
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of(current, target));
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

        assertNull(target.getRetiredAt());
        assertNull(target.getRetiredBy());
        assertEquals(IndexVersionStatus.ONLINE.name(), target.getStatus());
    }

    /**
     * 回收：构建中版本与已退役版本都不允许回收。
     *
     * <p>守护 {@code recycle} 的两道状态护栏：退役版本是回退目标，删了就回不去。
     */
    @Test
    void recycleShouldRejectBuildingAndRetiredVersions() {
        WbIndexVersion building = versionRow(30L, "v3", IndexVersionStatus.BUILDING.name());
        when(indexVersionDbService.getById(30L)).thenReturn(building);
        when(indexSetDbService.getById(5L)).thenReturn(indexSet());
        KnowledgeException buildingError = assertThrows(KnowledgeException.class,
                () -> service.recycle(30L));
        assertEquals(ErrorCode.INDEX_BUILDING_CONFLICT.getCode(), buildingError.getCode());

        WbIndexVersion retired = versionRow(31L, "v2", IndexVersionStatus.RETIRED.name());
        when(indexVersionDbService.getById(31L)).thenReturn(retired);
        KnowledgeException retiredError = assertThrows(KnowledgeException.class,
                () -> service.recycle(31L));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), retiredError.getCode());
        verify(milvusIndexPort, never()).drop(anyString());
        verify(indexVersionDbService, never()).removeById(anyLong());
    }

    /**
     * 集合初始化失败：版本行落 FAILED 并带上截断后的错误原因。
     *
     * <p>守护 {@code markFailed}：不置 FAILED 会让版本永远停在 CREATED、对账也看不出原因。
     */
    @Test
    void registerCollectionFailureShouldMarkVersionFailed() {
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(stubKb());
        stubProductCombo(file, "preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(indexVersionDbService.nextVersionNo(5L)).thenReturn("v1");
        AtomicReference<WbIndexVersion> saved = new AtomicReference<>();
        when(indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            WbIndexVersion v = inv.getArgument(0);
            v.setId(100L);
            saved.set(v);
            return true;
        });
        doThrow(new IllegalStateException("milvus 不可用"))
                .when(milvusIndexPort).ensureCollection("kb_1_v1", 1024);
        when(indexSetDbService.getByKb(1L)).thenReturn(null);

        service.onFileProductsReady(10L);

        assertEquals(IndexVersionStatus.FAILED.name(), saved.get().getStatus());
        assertTrue(saved.get().getBuildError().contains("集合初始化失败"), saved.get().getBuildError());
        verify(indexVersionDbService).updateById(saved.get());
        verify(indexSetDbService, never()).updatePublishedVersion(anyLong(), anyLong());
    }

    /**
     * 向量产物读取失败（装配返回 null）：版本行落 FAILED，不再追加。
     *
     * <p>守护 {@code onFileProductsReady} 的空产物兜底：静默跳过会让版本永远缺这批文件。
     */
    @Test
    void onFileProductsReadyWhenRowsUnavailableShouldMarkFailed() {
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(stubKb());
        stubProductCombo(file, "preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        when(indexRowAssembler.assemble(eq(10L), anyString(), any(), any())).thenReturn(null);
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        AtomicReference<WbIndexVersion> saved = new AtomicReference<>();
        when(indexVersionDbService.listByIndexSetId(5L)).thenAnswer(inv -> saved.get() == null
                ? List.of() : List.of(saved.get()));
        when(indexVersionDbService.nextVersionNo(5L)).thenReturn("v1");
        when(indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            WbIndexVersion v = inv.getArgument(0);
            v.setId(100L);
            v.setStatus(IndexVersionStatus.CREATED.name());
            saved.set(v);
            return true;
        });

        service.onFileProductsReady(10L);

        assertEquals(IndexVersionStatus.FAILED.name(), saved.get().getStatus());
        assertTrue(saved.get().getBuildError().contains("向量产物读取失败"), saved.get().getBuildError());
        // 新版本账本从 0 起（不是 null）：前端按数字格式化
        assertEquals(0, saved.get().getChunkCount());
        assertEquals(0, saved.get().getVectorCount());
        verify(milvusIndexPort, never()).append(anyString(), anyList());
    }

    /**
     * 组合注册撞版本号唯一键：重取版本号再存一次。
     *
     * <p>守护 {@code registerComboCollection} 的并发兜底：不重取版本号会第二次同样撞键、注册失败。
     */
    @Test
    void registerShouldRetryVersionNoOnDuplicateKey() {
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(stubKb());
        stubProductCombo(file, "preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(indexVersionDbService.nextVersionNo(5L)).thenReturn("v1", "v2");
        // 第一次保存撞 uk_set_version，重取版本号后成功
        when(indexVersionDbService.save(any(WbIndexVersion.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_set_version"))
                .thenAnswer(inv -> {
                    inv.<WbIndexVersion>getArgument(0).setId(100L);
                    return true;
                });
        when(indexSetDbService.getByKb(1L)).thenReturn(null);

        service.onFileProductsReady(10L);

        ArgumentCaptor<WbIndexVersion> captor = ArgumentCaptor.forClass(WbIndexVersion.class);
        verify(indexVersionDbService, org.mockito.Mockito.times(2)).save(captor.capture());
        // 同一版本行对象：重试前被改写成新版本号，最终落库与建集合都用 v2
        assertEquals("v2", captor.getValue().getVersionNo());
        verify(milvusIndexPort).ensureCollection("kb_1_v2", 1024);
    }

    /**
     * 无匹配切片的文件：按在线组合投递预处理补齐。
     *
     * <p>守护 {@code backfillFile} 的预处理投递分支：谓词写反会把该补的文件跳过。
     */
    @Test
    void backfillShouldEnqueuePreprocessWhenNoMatchingChunk() {
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(stubKb());
        stubProductCombo(file, "preproc-default-v2", "chunk-hybrid-v2", "embed-default-v2");
        stubOnlineCombo("v0", comboAll());
        when(chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of());
        WbPipelineStrategyVersion preprocessStrategy = new WbPipelineStrategyVersion();
        preprocessStrategy.setId(77L);
        when(strategyVersionDbService.getByTypeAndNameAndVersion("PREPROCESS", "preproc-default", "v1"))
                .thenReturn(preprocessStrategy);
        stubAppendTarget();

        service.onFileProductsReady(10L);

        verify(preprocessControlService).preprocess(10L, 77L, null);
        verify(chunkControlService, never()).chunk(anyLong(), anyLong(), any());
    }

    /**
     * 追加只统计带向量的行，并清掉历史失败原因。
     *
     * <p>守护 {@code updateLedger}：把无向量行也计入向量数会虚报；留着旧 buildError 会一直显示失败。
     */
    @Test
    void appendShouldCountOnlyVectorRowsAndClearBuildError() {
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(stubKb());
        stubProductCombo(file, "preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        WbIndexVersion active = versionRow(100L, "v1", IndexVersionStatus.READY.name());
        active.setComboSnapshot(JsonUtil.toJsonStr(comboAll()));
        active.setChunkCount(4);
        active.setVectorCount(3);
        active.setBuildError("上次失败");
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of(active));
        IndexRow withVector = indexRow();
        IndexRow withoutVector = indexRow();
        withoutVector.setChunkId("chunk-2");
        withoutVector.setVector(null);
        when(indexRowAssembler.assemble(eq(10L), anyString(), any(), any()))
                .thenReturn(List.of(withVector, withoutVector));
        when(milvusIndexPort.listChunkIds("kb_1_v1", 10L)).thenReturn(List.of("chunk-1", "chunk-2"));
        when(indexComboService.resolveBoundCombo(1L)).thenReturn(comboAll());
        // 追加后 READY 无变化，自动发布交给 self 代理（桩返回实例即可）
        when(indexSetDbService.getByKb(1L)).thenReturn(null);

        service.onFileProductsReady(10L);

        assertEquals(6, active.getChunkCount());
        assertEquals(4, active.getVectorCount());
        assertNull(active.getBuildError());
        verify(indexVersionDbService).updateById(active);
    }

    /**
     * 拓扑补齐：已有目标预处理链但缺目标切片 → 投递切片并显式绑定上游预处理产物。
     *
     * <p>守护 {@code backfillFile} 的切片补齐分支：漏带 upstreamProductId 会混用新旧预处理口径。
     */
    @Test
    void backfillShouldEnqueueChunkWithMatchedUpstreamProduct() {
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(stubKb());
        stubProductCombo(file, "preproc-default-v2", "chunk-hybrid-v2", "embed-default-v2");
        stubOnlineCombo("v0", comboAll());
        // 已有一条目标预处理口径的切片（策略不同） → 缺目标切片
        WbChunkSet existing = new WbChunkSet();
        existing.setId(1L);
        existing.setFileResultId(10L);
        existing.setChunkStrategyVersion("chunk-hybrid-v2");
        existing.setUpstreamProductId(100L);
        when(chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(existing));
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        WbPipelineStrategyVersion chunkStrategy = new WbPipelineStrategyVersion();
        chunkStrategy.setId(88L);
        when(strategyVersionDbService.getByTypeAndNameAndVersion("CHUNK", "chunk-hybrid", "v1"))
                .thenReturn(chunkStrategy);
        // 预处理产物快照：一条坏快照 + 一条匹配快照（坏快照只告警不中断）
        WbPipelineProduct broken = new WbPipelineProduct();
        broken.setId(500L);
        broken.setStage(PipelineStage.PREPROCESS.name());
        broken.setCapabilitySnapshot("not-json");
        WbPipelineProduct matched = new WbPipelineProduct();
        matched.setId(501L);
        matched.setStage(PipelineStage.PREPROCESS.name());
        matched.setCapabilitySnapshot("{\"type\":\"PREPROCESS\",\"name\":\"preproc-default\",\"version\":\"v1\"}");
        when(productDbService.listByFileResultId(10L)).thenReturn(List.of(broken, matched));
        stubAppendTarget();

        service.onFileProductsReady(10L);

        verify(chunkControlService).chunk(10L, 88L, 501L);
        verify(preprocessControlService, never()).preprocess(anyLong(), anyLong(), any());
    }

    /**
     * 拓扑补齐：切片产物已齐（目标策略 + 命中血缘）→ 不再投递，等手动向量化。
     *
     * <p>守护 {@code backfillFile} 的「已齐」分支：重复投递会撞执行键、白跑任务。
     */
    @Test
    void backfillShouldSkipWhenTargetChunkAlreadyExists() {
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(stubKb());
        stubProductCombo(file, "preproc-default-v2", "chunk-hybrid-v2", "embed-default-v2");
        stubOnlineCombo("v0", comboAll());
        WbChunkSet matchedChunk = new WbChunkSet();
        matchedChunk.setId(1L);
        matchedChunk.setFileResultId(10L);
        matchedChunk.setChunkStrategyVersion("chunk-hybrid-v1");
        matchedChunk.setUpstreamProductId(100L);
        when(chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(matchedChunk));
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        // 对抗性夹具（宽松）：若「已齐」判定被写反，补齐分支会真的走到投递，用 verify 抓这段差异
        WbPipelineStrategyVersion chunkStrategy = new WbPipelineStrategyVersion();
        chunkStrategy.setId(88L);
        lenient().when(strategyVersionDbService.getByTypeAndNameAndVersion("CHUNK", "chunk-hybrid", "v1"))
                .thenReturn(chunkStrategy);
        WbPipelineProduct preprocessProduct = new WbPipelineProduct();
        preprocessProduct.setId(501L);
        preprocessProduct.setStage(PipelineStage.PREPROCESS.name());
        preprocessProduct.setCapabilitySnapshot("{\"name\":\"preproc-default\",\"version\":\"v1\"}");
        lenient().when(productDbService.listByFileResultId(10L)).thenReturn(List.of(preprocessProduct));
        stubAppendTarget();

        service.onFileProductsReady(10L);

        verify(preprocessControlService, never()).preprocess(anyLong(), anyLong(), any());
        verify(chunkControlService, never()).chunk(anyLong(), anyLong(), any());
    }

    /**
     * 拓扑补齐中止：目标预处理策略行不存在 / 目标切片策略行不存在 / 上游预处理产物缺失。
     *
     * <p>守护 {@code backfillFile} 的三处中止：策略行或上游产物缺失时投递出去的任务必然失败。
     */
    @Test
    void backfillShouldStopWhenStrategyOrUpstreamMissing() {
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(stubKb());
        stubProductCombo(file, "preproc-default-v2", "chunk-hybrid-v2", "embed-default-v2");
        stubOnlineCombo("v0", comboAll());
        stubAppendTarget();
        // ① 无任何切片 → 走预处理投递；目标策略行不存在 → 中止
        when(chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of());
        when(strategyVersionDbService.getByTypeAndNameAndVersion("PREPROCESS", "preproc-default", "v1"))
                .thenReturn(null);

        service.onFileProductsReady(10L);

        verify(preprocessControlService, never()).preprocess(anyLong(), anyLong(), any());
        verify(chunkControlService, never()).chunk(anyLong(), anyLong(), any());
    }

    /**
     * 拓扑补齐中止：预处理口径已命中但目标切片策略行不存在。
     *
     * <p>守护 {@code backfillFile} 的切片策略缺失分支：投出去的切片任务会因策略不存在失败。
     */
    @Test
    void backfillShouldStopWhenChunkStrategyMissing() {
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(stubKb());
        stubProductCombo(file, "preproc-default-v2", "chunk-hybrid-v2", "embed-default-v2");
        stubOnlineCombo("v0", comboAll());
        WbChunkSet existing = new WbChunkSet();
        existing.setId(1L);
        existing.setFileResultId(10L);
        existing.setChunkStrategyVersion("chunk-hybrid-v2");
        existing.setUpstreamProductId(100L);
        when(chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(existing));
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        when(strategyVersionDbService.getByTypeAndNameAndVersion("CHUNK", "chunk-hybrid", "v1")).thenReturn(null);
        stubAppendTarget();

        service.onFileProductsReady(10L);

        verify(chunkControlService, never()).chunk(anyLong(), anyLong(), any());
        verify(preprocessControlService, never()).preprocess(anyLong(), anyLong(), any());
    }

    /**
     * 活跃版本只认未终结状态：同组合的已退役版本不算活跃，绑定开启时照常登记新组合版本。
     *
     * <p>守护 {@code findActiveVersion} 的状态过滤：把退役版本当活跃会让新组合永远登记不上。
     */
    @Test
    void registerShouldIgnoreRetiredVersionWithSameCombo() {
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(stubKb());
        stubProductCombo(file, "preproc-default-v1", "chunk-hybrid-v1", "embed-default-v1");
        WbIndexVersion retired = versionRow(90L, "v1", IndexVersionStatus.RETIRED.name());
        retired.setComboSnapshot(JsonUtil.toJsonStr(comboAll()));
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of(retired));
        when(indexVersionDbService.nextVersionNo(5L)).thenReturn("v2");
        when(indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            inv.<WbIndexVersion>getArgument(0).setId(102L);
            return true;
        });
        when(indexSetDbService.getByKb(1L)).thenReturn(null);

        service.onFileProductsReady(10L);

        ArgumentCaptor<WbIndexVersion> versionCaptor = ArgumentCaptor.forClass(WbIndexVersion.class);
        verify(indexVersionDbService).save(versionCaptor.capture());
        assertEquals("v2", versionCaptor.getValue().getVersionNo());
        assertEquals(IndexVersionStatus.READY.name(), versionCaptor.getValue().getStatus());
        verify(milvusIndexPort).ensureCollection("kb_1_v2", 1024);
    }

    /**
     * 已有切片但血统不是目标预处理：按目标预处理投递补齐（不能因为"有切片"就跳过）。
     *
     * <p>守护 {@code backfillFile} 的预处理谓词：判定写成恒假会漏投，写成恒真会跳过必补环节。
     */
    @Test
    void backfillShouldDispatchPreprocessWhenExistingChunkHasOtherLineage() {
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(stubKb());
        stubProductCombo(file, "preproc-default-v2", "chunk-hybrid-v2", "embed-default-v2");
        stubOnlineCombo("v0", comboAll());
        WbChunkSet chunk = new WbChunkSet();
        chunk.setId(1L);
        chunk.setFileResultId(10L);
        chunk.setChunkStrategyVersion("chunk-hybrid-v1");
        chunk.setUpstreamProductId(100L);
        when(chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(chunk));
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-legacy-v1");
        WbPipelineStrategyVersion preprocessStrategy = new WbPipelineStrategyVersion();
        preprocessStrategy.setId(77L);
        lenient().when(strategyVersionDbService.getByTypeAndNameAndVersion("PREPROCESS", "preproc-default", "v1"))
                .thenReturn(preprocessStrategy);
        WbPipelineProduct preprocessProduct = new WbPipelineProduct();
        preprocessProduct.setId(501L);
        preprocessProduct.setStage(PipelineStage.PREPROCESS.name());
        preprocessProduct.setCapabilitySnapshot("{\"name\":\"preproc-default\",\"version\":\"v1\"}");
        lenient().when(productDbService.listByFileResultId(10L)).thenReturn(List.of(preprocessProduct));
        stubAppendTarget();

        service.onFileProductsReady(10L);

        verify(preprocessControlService).preprocess(10L, 77L, null);
        verify(chunkControlService, never()).chunk(anyLong(), anyLong(), any());
    }

    /**
     * 回退补齐：切片策略不符而向量齐备 → 仍按缺口投递切片。
     *
     * <p>守护 {@code compensate} 的切片谓词：判定恒真会把该补的文件当成已齐、只做补偿构建。
     */
    @Test
    void compensateShouldDispatchChunkWhenChunkStrategyMismatches() {
        WbIndexVersion target = versionRow(15L, "v1", IndexVersionStatus.RETIRED.name());
        WbIndexVersion current = versionRow(10L, "v2", IndexVersionStatus.ONLINE.name());
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(10L);
        when(indexVersionDbService.getById(15L)).thenReturn(target);
        when(indexVersionDbService.getById(10L)).thenReturn(current);
        when(indexSetDbService.getById(5L)).thenReturn(set);
        when(fileResultDbService.listByKb(1L)).thenReturn(List.of(file(10L)));
        WbChunkSet chunk = new WbChunkSet();
        chunk.setId(1L);
        chunk.setFileResultId(10L);
        chunk.setChunkStrategyVersion("chunk-legacy-v1");
        chunk.setUpstreamProductId(100L);
        when(chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(chunk));
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        WbEmbeddingSet embed = new WbEmbeddingSet();
        embed.setId(2L);
        embed.setFileResultId(10L);
        embed.setStrategyVersion("embed-default-v1");
        when(embeddingSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(embed));
        WbPipelineStrategyVersion chunkStrategy = new WbPipelineStrategyVersion();
        chunkStrategy.setId(88L);
        when(strategyVersionDbService.getByTypeAndNameAndVersion("CHUNK", "chunk-hybrid", "v1"))
                .thenReturn(chunkStrategy);
        WbPipelineProduct preprocessProduct = new WbPipelineProduct();
        preprocessProduct.setId(501L);
        preprocessProduct.setStage(PipelineStage.PREPROCESS.name());
        preprocessProduct.setCapabilitySnapshot("{\"name\":\"preproc-default\",\"version\":\"v1\"}");
        when(productDbService.listByFileResultId(10L)).thenReturn(List.of(preprocessProduct));
        service.rollback(15L);

        verify(chunkControlService).chunk(10L, 88L, 501L);
    }

    /**
     * 有切片产物但取不到匹配的上游预处理产物：补齐中止，不投递指向 0 号产物的任务。
     *
     * <p>守护 {@code preprocessProductIdOf} 的无匹配返回：返回 0 会让下游拿 0 当上游。
     */
    @Test
    void backfillShouldStopWhenNoMatchingPreprocessProduct() {
        WbFileResult file = file(10L);
        when(fileResultDbService.getById(10L)).thenReturn(file);
        when(knowledgeBaseDbService.getById(1L)).thenReturn(stubKb());
        stubProductCombo(file, "preproc-default-v2", "chunk-hybrid-v2", "embed-default-v2");
        stubOnlineCombo("v0", comboAll());
        WbChunkSet existing = new WbChunkSet();
        existing.setId(1L);
        existing.setFileResultId(10L);
        existing.setChunkStrategyVersion("chunk-hybrid-v2");
        existing.setUpstreamProductId(100L);
        when(chunkSetDbService.listByFileResultIds(List.of(10L))).thenReturn(List.of(existing));
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
        WbPipelineStrategyVersion chunkStrategy = new WbPipelineStrategyVersion();
        chunkStrategy.setId(88L);
        when(strategyVersionDbService.getByTypeAndNameAndVersion("CHUNK", "chunk-hybrid", "v1"))
                .thenReturn(chunkStrategy);
        // 产物行只有 CHUNK 环节，取不到匹配的预处理产物
        WbPipelineProduct chunkProduct = new WbPipelineProduct();
        chunkProduct.setId(600L);
        chunkProduct.setStage(PipelineStage.CHUNK.name());
        when(productDbService.listByFileResultId(10L)).thenReturn(List.of(chunkProduct));
        stubAppendTarget();

        service.onFileProductsReady(10L);

        verify(chunkControlService, never()).chunk(anyLong(), anyLong(), any());
    }

    /**
     * 版本行没有组合快照：发布按 40001 拒绝并给出文案。
     *
     * <p>守护 {@code requireVersionCombo} 的 null 校验：漏校验会在解析后空指针。
     */
    @Test
    void publishShouldRejectVersionWithoutComboSnapshot() {
        WbIndexVersion noCombo = versionRow(20L, "v2", IndexVersionStatus.READY.name());
        noCombo.setComboSnapshot(null);
        when(indexVersionDbService.getById(20L)).thenReturn(noCombo);
        lenient().when(indexSetDbService.getById(5L)).thenReturn(indexSet());

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.publish(20L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("版本组合快照缺失"), e.getMessage());
    }

    /** 知识库夹具（绑定开关开启） */
    private WbKnowledgeBase stubKb() {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(1L);
        kb.setStrategyBindingEnabled(1);
        return kb;
    }

    /** 追加目标夹具：无活跃版本时新建组合版本（绑定开启的自动注册路径） */
    private void stubAppendTarget() {
        when(indexSetDbService.getOrCreateByKb(1L)).thenReturn(indexSet());
        when(indexVersionDbService.listByIndexSetId(5L)).thenReturn(List.of());
        when(indexVersionDbService.nextVersionNo(5L)).thenReturn("v1");
        when(indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            inv.<WbIndexVersion>getArgument(0).setId(100L);
            return true;
        });
    }

    /** 产物链夹具：EMBED 行 → 切片行 → 预处理血缘 */
    private void stubProductCombo(WbFileResult file, String preprocess, String chunk, String embed) {
        WbEmbeddingSet embedRow = new WbEmbeddingSet();
        embedRow.setId(2L);
        embedRow.setFileResultId(file.getId());
        embedRow.setChunkSetRef(1L);
        embedRow.setStrategyVersion(embed);
        embedRow.setDimension(1024);
        when(embeddingSetDbService.listByFileResultIds(List.of(file.getId()))).thenReturn(List.of(embedRow));
        WbChunkSet chunkRow = new WbChunkSet();
        chunkRow.setId(1L);
        chunkRow.setFileResultId(file.getId());
        chunkRow.setChunkStrategyVersion(chunk);
        chunkRow.setUpstreamProductId(100L);
        when(chunkSetDbService.getById(1L)).thenReturn(chunkRow);
        lenient().when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn(preprocess);
        lenient().when(indexRowAssembler.assemble(eq(file.getId()), eq("userA"), eq(chunkRow), eq(embedRow)))
                .thenReturn(List.of(indexRow()));
    }

    /** 在线版本夹具：集合指针 + 版本行（组合由入参指定） */
    private void stubOnlineCombo(String versionNo, ComboSnapshot combo) {
        WbIndexSet set = indexSet();
        set.setCurrentPublishedVersionId(50L);
        when(indexSetDbService.getByKb(1L)).thenReturn(set);
        WbIndexVersion online = new WbIndexVersion();
        online.setId(50L);
        online.setIndexSetId(5L);
        online.setVersionNo(versionNo);
        online.setStatus(IndexVersionStatus.ONLINE.name());
        online.setComboSnapshot(JsonUtil.toJsonStr(combo));
        when(indexVersionDbService.getById(50L)).thenReturn(online);
    }
}
