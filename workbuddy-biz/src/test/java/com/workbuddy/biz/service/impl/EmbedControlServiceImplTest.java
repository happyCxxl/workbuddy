package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.KbEmbeddingRecordDbService;
import com.workbuddy.biz.service.db.KbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.KbFileResultDbService;
import com.workbuddy.biz.service.db.KnowledgeBaseDbService;
import com.workbuddy.biz.service.db.KbPipelineProductDbService;
import com.workbuddy.biz.service.db.KbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.KbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.KbPipelineTaskDbService;
import com.workbuddy.biz.service.db.KbSourceFileDbService;
import com.workbuddy.biz.service.db.KbStrategyBindingDbService;
import com.workbuddy.biz.service.support.ChainStorageSupport;
import com.workbuddy.biz.service.support.FileResultAccessGuard;
import com.workbuddy.biz.service.support.EmbedVoAssembler;
import com.workbuddy.biz.service.support.StageStrategySupport;
import com.workbuddy.biz.service.support.TaskDetailSupport;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.biz.task.support.TaskTriggerSupport;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.entity.KnowledgeBase;
import com.workbuddy.common.domain.entity.KbEmbeddingRecord;
import com.workbuddy.common.domain.entity.KbEmbeddingSet;
import com.workbuddy.common.domain.entity.KbFileResult;
import com.workbuddy.common.domain.entity.KbPipelineProduct;
import com.workbuddy.common.domain.entity.KbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.KbPipelineTask;
import com.workbuddy.common.domain.entity.KbStrategyBinding;
import com.workbuddy.common.dto.response.embed.EmbedDetailVO;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.service.FileStorage;
import com.workbuddy.model.catalog.StaticModelCatalog;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import com.workbuddy.worker.embedding.EmbedProperties;
import com.workbuddy.worker.embedding.strategy.EmbedStrategy;
import com.workbuddy.worker.embedding.strategy.EmbedStrategyParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向量化控制面服务单测：策略解析两档（显式指定 / 知识库绑定）/ 上游切片产物校验 /
 * 窗口前置校验（触发时明确报错）/ 执行键查重 / 建任务快照入队 / 详情组装。
 *
 * @author cxxl
 */
@ExtendWith(MockitoExtension.class)
class EmbedControlServiceImplTest {

    /** 产物所在的数据源 */
    private static final Long SOURCE_ID = 1L;

    /** 上游产物的产出任务 ID（门禁按它的状态判定） */
    private static final Long UPSTREAM_TASK_ID = 40L;

    @Mock
    private KbFileResultDbService fileResultDbService;
    @Mock
    private KnowledgeBaseDbService knowledgeBaseDbService;
    @Mock
    private KbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private KbPipelineProductDbService pipelineProductDbService;
    @Mock
    private KbPipelineStrategyVersionDbService strategyVersionDbService;
    @Mock
    private KbPipelineStepLogDbService stepLogDbService;
    @Mock
    private TaskQueueSupport taskQueue;
    @Mock
    private KbEmbeddingSetDbService embeddingSetDbService;
    @Mock
    private KbEmbeddingRecordDbService embeddingRecordDbService;
    @Mock
    private KbStrategyBindingDbService strategyBindingDbService;
    @Mock
    private KbSourceFileDbService sourceFileDbService;
    @Mock
    private StorageRouter storageRouter;
    @Mock
    private FileStorage fileStorage;

    private EmbedControlServiceImpl service;

    @BeforeEach
    void setUp() {
        // 触发/详情助手为纯委托类、组装器为纯映射类，用真实实例（mock 会让 VO 组装返回 null，断言失真）
        SecurityTestSupport.loginViewer();
        boundKnowledgeBase(10L);
        FileResultAccessGuard accessGuard = new FileResultAccessGuard(fileResultDbService, knowledgeBaseDbService);
        ChainStorageSupport chainStorage = new ChainStorageSupport(fileResultDbService, sourceFileDbService,
                fileStorage, storageRouter);
        service = new EmbedControlServiceImpl(pipelineProductDbService, stepLogDbService,
                embeddingSetDbService, embeddingRecordDbService, new EmbedVoAssembler(),
                new EmbedStrategyParser(new EmbedProperties(), new StaticModelCatalog()),
                new ChunkStrategyParser(new ChunkProperties()),
                new EmbedProperties(), new ChunkProperties(),
                new StageStrategySupport(strategyVersionDbService, strategyBindingDbService, knowledgeBaseDbService),
                new TaskTriggerSupport(pipelineTaskDbService, chainStorage, taskQueue),
                new TaskDetailSupport(pipelineTaskDbService, stepLogDbService, pipelineProductDbService), accessGuard,
                new UpstreamProductGate(pipelineTaskDbService));
        // 上游产物的产出任务默认成功：门禁放行；不放行的状态由各用例自行改写（宽松桩）
        lenient().when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID)).thenReturn(upstreamTask(PipelineTaskStatus.SUCCESS));
    }

    /** 上游产物的产出任务（环节 CHUNK、归属文件结果 10） */
    private KbPipelineTask upstreamTask(PipelineTaskStatus status) {
        KbPipelineTask task = new KbPipelineTask();
        task.setId(UPSTREAM_TASK_ID);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.CHUNK.name());
        task.setStatus(status.name());
        return task;
    }

    private KbFileResult fileResult() {
        KbFileResult fileResult = new KbFileResult();
        fileResult.setId(10L);
        fileResult.setKnowledgeBaseId(10L);
        return fileResult;
    }

    /** CHUNK 产物（capabilitySnapshot 可指定切片策略快照；空=跳过窗口预检） */
    private KbPipelineProduct chunkProduct(String capabilitySnapshot) {
        KbPipelineProduct product = new KbPipelineProduct();
        product.setId(50L);
        product.setTaskId(UPSTREAM_TASK_ID);
        product.setFileResultId(10L);
        product.setStage(PipelineStage.CHUNK.name());
        product.setCapabilitySnapshot(capabilitySnapshot);
        product.setArtifactId("abc".repeat(22));
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        return product;
    }

    private void stubCommon() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(chunkProduct(null));
        stubBoundStrategy();
    }

    /** 绑定档桩：KB 10 → 绑定行 → 启用中的策略版本行 66（显式指定档的用例不命中它，按宽松桩登记） */
    private void stubBoundStrategy() {
        lenient().when(strategyBindingDbService.getByKbAndType(10L, EmbedStrategy.TYPE)).thenReturn(binding());
        lenient().when(strategyVersionDbService.getById(66L))
                .thenReturn(embedVersion(66L, "embed-bound", "v3", RowStatus.ACTIVE.name()));
    }

    /** 向量化执行键检查：文件结果 10 + 上游切片产物 + 策略版本（0 表示该环节无策略） */
    private void existingEmbedTask(Long upstreamProductId, Long strategyVersionId, KbPipelineTask task) {
        when(pipelineTaskDbService.getByExecKey(10L, PipelineStage.EMBED.name(), upstreamProductId,
                strategyVersionId)).thenReturn(task);
    }

    /** 知识库夹具（绑定开关未显式关闭 → 视为开启） */
    private KnowledgeBase knowledgeBase() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(10L);
        kb.setStrategyBindingEnabled(1);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        return kb;
    }

    /** 绑定开关关闭的知识库 */
    private KnowledgeBase bindingOffKnowledgeBase() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(10L);
        kb.setStrategyBindingEnabled(0);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        return kb;
    }

    /** 有效绑定行：KB 10 绑定 EMBED 策略版本 66 */
    private KbStrategyBinding binding() {
        KbStrategyBinding binding = new KbStrategyBinding();
        binding.setId(1L);
        binding.setKnowledgeBaseId(10L);
        binding.setStrategyType(EmbedStrategy.TYPE);
        binding.setStrategyVersionId(66L);
        return binding;
    }

    @Test
    void boundStrategyShouldDriveTaskSnapshotAndEnqueue() {
        stubCommon();
        existingEmbedTask(50L, 66L, null);
        when(pipelineTaskDbService.save(any(KbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbPipelineTask.class).setId(41L);
            return true;
        });

        var response = service.embed(10L, null, 50L);

        assertEquals(41L, response.getPipelineTaskId());
        assertEquals("embed-bound-v3", response.getStrategyVersion());
        ArgumentCaptor<KbPipelineTask> captor = ArgumentCaptor.forClass(KbPipelineTask.class);
        verify(pipelineTaskDbService).save(captor.capture());
        KbPipelineTask task = captor.getValue();
        assertEquals(PipelineStage.EMBED.name(), task.getStage());
        assertEquals(PipelineTaskStatus.QUEUED.name(), task.getStatus());
        assertEquals(50L, task.getUpstreamProductId());
        assertEquals(66L, task.getStrategyVersionId());
        assertNotNull(task.getStrategySnapshot());
        assertTrue(task.getStrategySnapshot().contains("text-embedding-v4"));
        verify(taskQueue).enqueue(41L);
    }

    @Test
    void unboundStrategyShouldReject40438() {
        // 知识库未绑定：不再取"启用中最新"兜底，一律拒绝
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(strategyBindingDbService.getByKbAndType(10L, EmbedStrategy.TYPE)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void bindingSwitchOffShouldReject40438() {
        // 绑定开关关闭：即使库内存在绑定行也不生效
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(knowledgeBaseDbService.getActiveById(10L)).thenReturn(bindingOffKnowledgeBase());

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(strategyBindingDbService, never()).getByKbAndType(any(), any());
        verify(pipelineTaskDbService, never()).save(any());
    }

    @Test
    void missingUpstreamProductShouldThrow40436() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        stubBoundStrategy();

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, null));
        assertEquals(ErrorCode.TASK_UPSTREAM_MISSING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void failedUpstreamTaskShouldThrow40439() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        stubBoundStrategy();
        when(pipelineProductDbService.getById(50L)).thenReturn(chunkProduct(null));
        // 产出任务已失败，产物行却已经落盘（孤儿产物）：下游门禁拒绝消费
        when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID)).thenReturn(upstreamTask(PipelineTaskStatus.FAILED));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, 50L));

        assertEquals(ErrorCode.TASK_UPSTREAM_NOT_READY, e.getErrorCode());
        assertTrue(e.getMessage().contains(PipelineTaskStatus.FAILED.name()), e.getMessage());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void unknownStrategyVersionShouldThrow40433() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(strategyVersionDbService.getById(999L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, 999L, null));
        assertEquals(ErrorCode.STRATEGY_VERSION_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void boundStrategyShouldBeUsedWhenNoExplicitVersion() {
        stubCommon();
        existingEmbedTask(50L, 66L, null);
        when(pipelineTaskDbService.save(any(KbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbPipelineTask.class).setId(43L);
            return true;
        });

        var response = service.embed(10L, null, 50L);

        assertEquals("embed-bound-v3", response.getStrategyVersion());
    }

    @Test
    void staleBindingShouldReject40438() {
        // 绑定行指向的策略版本行缺失：不再回退"启用中最新"，一律拒绝
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(knowledgeBaseDbService.getActiveById(10L)).thenReturn(knowledgeBase());
        when(strategyBindingDbService.getByKbAndType(10L, EmbedStrategy.TYPE)).thenReturn(binding());
        when(strategyVersionDbService.getById(66L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
    }

    @Test
    void explicitStrategyVersionShouldResolveFromDb() {
        stubCommon();
        when(strategyVersionDbService.getById(77L))
                .thenReturn(embedVersion(77L, "embed-strict", "v2", RowStatus.ACTIVE.name()));
        existingEmbedTask(50L, 77L, null);
        when(pipelineTaskDbService.save(any(KbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbPipelineTask.class).setId(45L);
            return true;
        });

        var response = service.embed(10L, 77L, 50L);

        assertEquals("embed-strict-v2", response.getStrategyVersion());
        verify(strategyBindingDbService, never()).getByKbAndType(any(), any());
    }

    @Test
    void windowIncompatibleShouldThrow40435() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        stubBoundStrategy();
        // 切片策略 softMaxLen=20000 超 text-embedding-v4 窗口（8192×1.5=12288）
        when(pipelineProductDbService.getById(50L))
                .thenReturn(chunkProduct("{\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\","
                        + "\"params\":{\"softMaxLen\":20000}}}}"));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, 50L));
        assertEquals(ErrorCode.EMBED_MODEL_INCOMPATIBLE, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void corruptChunkCapabilitySnapshotShouldSkipWindowCheck() {
        // 上游切片产物行的策略快照坏：窗口兼容校验跳过（留空），触发照常入队，不造默认策略
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        stubBoundStrategy();
        when(pipelineProductDbService.getById(50L)).thenReturn(chunkProduct("{oops"));
        existingEmbedTask(50L, 66L, null);
        when(pipelineTaskDbService.save(any(KbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbPipelineTask.class).setId(46L);
            return true;
        });

        var response = service.embed(10L, null, 50L);

        assertEquals(46L, response.getPipelineTaskId());
        assertNotNull(response.getStrategyVersion());
        verify(taskQueue).enqueue(46L);
    }

    @Test
    void windowCompatibleShouldProceed() {
        stubCommon();
        existingEmbedTask(50L, 66L, null);
        when(pipelineTaskDbService.save(any(KbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbPipelineTask.class).setId(44L);
            return true;
        });

        var response = service.embed(10L, null, 50L);

        assertEquals(44L, response.getPipelineTaskId());
    }

    @Test
    void runningShouldReject40431() {
        stubCommon();
        KbPipelineTask running = new KbPipelineTask();
        running.setId(41L);
        running.setStatus(PipelineTaskStatus.RUNNING.name());
        existingEmbedTask(50L, 66L, running);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        // 拒绝文案带任务号与状态：用户能看出是哪条任务占着执行键
        assertTrue(e.getMessage().contains("taskId=41"), e.getMessage());
        assertTrue(e.getMessage().contains(PipelineTaskStatus.RUNNING.name()), e.getMessage());
        verify(pipelineTaskDbService, never()).save(any());
    }

    @Test
    void existingQueuedTaskShouldReject40431() {
        stubCommon();
        KbPipelineTask queued = new KbPipelineTask();
        queued.setId(41L);
        queued.setStatus(PipelineTaskStatus.QUEUED.name());
        existingEmbedTask(50L, 66L, queued);

        // 执行键命中即拒绝，触发入口不再补投唤醒；QUEUED 补投归启动补偿与低频扫描
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void detailShouldAssembleSummaryAndRecords() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        KbPipelineTask task = new KbPipelineTask();
        task.setId(41L);
        task.setStage(PipelineStage.EMBED.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.EMBED.name())).thenReturn(task);
        KbPipelineProduct product = new KbPipelineProduct();
        product.setArtifactId("abc".repeat(16));
        // 产物行记下对象位置：详情据此取向量集合
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        when(pipelineProductDbService.getById(50L)).thenReturn(product);
        KbEmbeddingSet set = new KbEmbeddingSet();
        set.setId(70L);
        set.setEmbeddingSetId("es-cs-1-embed-default-v1");
        set.setStrategyVersion("embed-default-v1");
        set.setModel("text-embedding-v4");
        set.setDimension(1024);
        set.setMetric("COSINE");
        set.setNormalized(true);
        set.setRecordCount(3);
        set.setCachedCount(1);
        set.setArtifactId("abc".repeat(16));
        set.setStorageType(StorageType.MINIO.getCode());
        set.setStorageSourceId(SOURCE_ID);
        set.setBucket("artifacts");
        when(embeddingSetDbService.getByArtifactId("abc".repeat(16))).thenReturn(set);
        KbEmbeddingRecord cached = new KbEmbeddingRecord();
        cached.setEmbeddingId("emb-0001");
        cached.setStatus("CACHED");
        cached.setCacheHit(true);
        KbEmbeddingRecord fresh = new KbEmbeddingRecord();
        fresh.setEmbeddingId("emb-0002");
        fresh.setStatus("SUCCESS");
        KbEmbeddingRecord skipped = new KbEmbeddingRecord();
        skipped.setEmbeddingId("emb-0003");
        skipped.setStatus("SKIPPED");
        when(embeddingRecordDbService.listByEmbeddingSetId(70L)).thenReturn(List.of(cached, fresh, skipped));

        EmbedDetailVO detail = service.embedDetail(10L, null);

        assertEquals(41L, detail.getTaskId());
        assertNotNull(detail.getSummary());
        assertEquals(3, detail.getSummary().getRecordCount());
        assertEquals(1, detail.getSummary().getCachedCount());
        assertEquals(1, detail.getSummary().getSuccessCount());
        assertEquals(0, detail.getSummary().getFailedCount());
        assertEquals(1, detail.getSummary().getSkippedCount());
        assertEquals(3, detail.getRecords().size());
    }

    @Test
    void detailWithoutTaskShouldReturnEmptyRecords() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.EMBED.name())).thenReturn(null);

        EmbedDetailVO detail = service.embedDetail(10L, null);

        assertNotNull(detail.getRecords());
        assertTrue(detail.getRecords().isEmpty());
    }

    private KbPipelineStrategyVersion embedVersion(Long id, String name, String version, String status) {
        KbPipelineStrategyVersion row = new KbPipelineStrategyVersion();
        row.setId(id);
        row.setType(EmbedStrategy.TYPE);
        row.setName(name);
        row.setVersion(version);
        row.setConfigSnapshot("{\"model\":\"text-embedding-v4\"}");
        row.setStatus(status);
        return row;
    }
    @Test
    void otherUserFileResultShouldReject40401() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        // 归属看的是知识库归属：换一个登录用户，越权与"不存在"同样返回 40401
        SecurityTestSupport.loginOtherUser();

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, 50L));
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }


    /** 让指定知识库归当前登录用户所有（归属校验要能过） */
    private void boundKnowledgeBase(Long id) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        lenient().when(knowledgeBaseDbService.getActiveById(id)).thenReturn(kb);
    }

}
