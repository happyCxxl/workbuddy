package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbEmbeddingRecordDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.support.ChainStorageSupport;
import com.workbuddy.biz.service.support.FileResultAccessGuard;
import com.workbuddy.biz.service.support.EmbedVoAssembler;
import com.workbuddy.biz.service.support.StageStrategySupport;
import com.workbuddy.biz.service.support.TaskDetailSupport;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.biz.task.support.TaskTriggerSupport;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbStrategyBinding;
import com.workbuddy.common.dto.response.embed.EmbedDetailVO;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
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
 */
@ExtendWith(MockitoExtension.class)
class EmbedControlServiceImplTest {

    /** 产物所在的数据源 */
    private static final Long SOURCE_ID = 1L;

    /** 上游产物的产出任务 ID（门禁按它的状态判定） */
    private static final Long UPSTREAM_TASK_ID = 40L;

    @Mock
    private WbFileResultDbService fileResultDbService;
    @Mock
    private WbKnowledgeBaseDbService knowledgeBaseDbService;
    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private WbPipelineProductDbService pipelineProductDbService;
    @Mock
    private WbPipelineStrategyVersionDbService strategyVersionDbService;
    @Mock
    private WbPipelineStepLogDbService stepLogDbService;
    @Mock
    private TaskQueueSupport taskQueue;
    @Mock
    private WbEmbeddingSetDbService embeddingSetDbService;
    @Mock
    private WbEmbeddingRecordDbService embeddingRecordDbService;
    @Mock
    private WbStrategyBindingDbService strategyBindingDbService;
    @Mock
    private WbSourceFileDbService sourceFileDbService;
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
                new EmbedStrategyParser(new EmbedProperties()),
                new ChunkStrategyParser(new ChunkProperties()),
                new EmbedProperties(), new ChunkProperties(),
                new StageStrategySupport(strategyVersionDbService, strategyBindingDbService, knowledgeBaseDbService),
                new TaskTriggerSupport(pipelineTaskDbService, chainStorage, taskQueue),
                new TaskDetailSupport(pipelineTaskDbService, stepLogDbService, pipelineProductDbService), accessGuard,
                new UpstreamProductGate(pipelineTaskDbService));
        // 上游产物的产出任务默认成功：门禁放行；不放行的状态由各用例自行改写（宽松桩）
        lenient().when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID))
                .thenReturn(upstreamTask(PipelineTaskStatus.SUCCESS));
    }

    /** 上游产物的产出任务（环节 CHUNK、归属文件结果 10） */
    private WbPipelineTask upstreamTask(PipelineTaskStatus status) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(UPSTREAM_TASK_ID);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.CHUNK.name());
        task.setStatus(status.name());
        return task;
    }

    private WbFileResult fileResult() {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setKnowledgeBaseId(10L);
        return fileResult;
    }

    /** CHUNK 产物（capabilitySnapshot 可指定切片策略快照；空=跳过窗口预检） */
    private WbPipelineProduct chunkProduct(String capabilitySnapshot) {
        WbPipelineProduct product = new WbPipelineProduct();
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
    private void existingEmbedTask(Long upstreamProductId, Long strategyVersionId, WbPipelineTask task) {
        when(pipelineTaskDbService.getByExecKey(10L, PipelineStage.EMBED.name(), upstreamProductId,
                strategyVersionId)).thenReturn(task);
    }

    /** 知识库夹具（绑定开关未显式关闭 → 视为开启） */
    private WbKnowledgeBase knowledgeBase() {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(10L);
        kb.setStrategyBindingEnabled(1);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        return kb;
    }

    /** 绑定开关关闭的知识库 */
    private WbKnowledgeBase bindingOffKnowledgeBase() {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(10L);
        kb.setStrategyBindingEnabled(0);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        return kb;
    }

    /** 有效绑定行：KB 10 绑定 EMBED 策略版本 66 */
    private WbStrategyBinding binding() {
        WbStrategyBinding binding = new WbStrategyBinding();
        binding.setId(1L);
        binding.setKnowledgeBaseId(10L);
        binding.setStrategyType(EmbedStrategy.TYPE);
        binding.setStrategyVersionId(66L);
        return binding;
    }

    /**
     * 绑定策略档：任务快照带策略、入队并回填任务号。
     *
     * <p>守护 {@code embed}：快照缺策略或漏入队即失败。
     */
    @Test
    void boundStrategyShouldDriveTaskSnapshotAndEnqueue() {
        stubCommon();
        existingEmbedTask(50L, 66L, null);
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineTask.class).setId(41L);
            return true;
        });

        var response = service.embed(10L, null, 50L);

        assertEquals(41L, response.getPipelineTaskId());
        assertEquals("embed-bound-v3", response.getStrategyVersion());
        ArgumentCaptor<WbPipelineTask> captor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(pipelineTaskDbService).save(captor.capture());
        WbPipelineTask task = captor.getValue();
        assertEquals(PipelineStage.EMBED.name(), task.getStage());
        assertEquals(PipelineTaskStatus.QUEUED.name(), task.getStatus());
        assertEquals(50L, task.getUpstreamProductId());
        assertEquals(66L, task.getStrategyVersionId());
        assertNotNull(task.getStrategySnapshot());
        assertTrue(task.getStrategySnapshot().contains("text-embedding-v4"));
        verify(taskQueue).enqueue(41L);
    }

    /**
     * 未绑定策略按 40438 拒绝。
     *
     * <p>守护 {@code embed}：无绑定仍建任务即失败。
     */
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

    /**
     * 绑定开关关闭（测评模式）按 40438 拒绝。
     *
     * <p>守护 {@code embed}：测评库被隐式绑定即失败。
     */
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

    /**
     * 未指定上游切片产物按 40436 拒绝。
     *
     * <p>守护 {@code embed}：缺上游仍建任务即失败。
     */
    @Test
    void missingUpstreamProductShouldThrow40436() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        stubBoundStrategy();

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, null));
        assertEquals(ErrorCode.TASK_UPSTREAM_MISSING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    /**
     * 上游产出任务未成功按 40439 拒绝。
     *
     * <p>守护 {@code embed}：失败上游的产物被消费即失败。
     */
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

    /**
     * 显式策略版本不存在按 40433 拒绝。
     *
     * <p>守护 {@code embed}：未知版本被采用即失败。
     */
    @Test
    void unknownStrategyVersionShouldThrow40433() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(strategyVersionDbService.getById(999L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, 999L, null));
        assertEquals(ErrorCode.STRATEGY_VERSION_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 未指定版本时取知识库绑定策略。
     *
     * <p>守护 {@code embed}：绑定档未生效即失败。
     */
    @Test
    void boundStrategyShouldBeUsedWhenNoExplicitVersion() {
        stubCommon();
        existingEmbedTask(50L, 66L, null);
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineTask.class).setId(43L);
            return true;
        });

        var response = service.embed(10L, null, 50L);

        assertEquals("embed-bound-v3", response.getStrategyVersion());
    }

    /**
     * 绑定行指向的版本已失效按 40438 拒绝。
     *
     * <p>守护 {@code embed}：失效绑定被采用即失败。
     */
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

    /**
     * 显式策略版本优先于绑定。
     *
     * <p>守护 {@code embed}：显式档被绑定覆盖即失败。
     */
    @Test
    void explicitStrategyVersionShouldResolveFromDb() {
        stubCommon();
        when(strategyVersionDbService.getById(77L))
                .thenReturn(embedVersion(77L, "embed-strict", "v2", RowStatus.ACTIVE.name()));
        existingEmbedTask(50L, 77L, null);
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineTask.class).setId(45L);
            return true;
        });

        var response = service.embed(10L, 77L, 50L);

        assertEquals("embed-strict-v2", response.getStrategyVersion());
        verify(strategyBindingDbService, never()).getByKbAndType(any(), any());
    }

    /**
     * 窗口预检：切片最大片长超模型窗口按 40435 拒绝。
     *
     * <p>守护 {@code embed}：超窗策略被执行即失败。
     */
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

    /**
     * 窗口预检：切片策略快照损坏时跳过校验并照常入队。
     *
     * <p>守护 {@code embed}：损坏快照阻断触发即失败。
     */
    @Test
    void corruptChunkCapabilitySnapshotShouldSkipWindowCheck() {
        // 上游切片产物行的策略快照坏：窗口兼容校验跳过（留空），触发照常入队，不造默认策略
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        stubBoundStrategy();
        when(pipelineProductDbService.getById(50L)).thenReturn(chunkProduct("{oops"));
        existingEmbedTask(50L, 66L, null);
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineTask.class).setId(46L);
            return true;
        });

        var response = service.embed(10L, null, 50L);

        assertEquals(46L, response.getPipelineTaskId());
        assertNotNull(response.getStrategyVersion());
        verify(taskQueue).enqueue(46L);
    }

    /**
     * 窗口预检：无切片策略快照时跳过校验。
     *
     * <p>守护 {@code embed}：无快照被拦即失败。
     */
    @Test
    void windowCompatibleShouldProceed() {
        stubCommon();
        existingEmbedTask(50L, 66L, null);
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineTask.class).setId(44L);
            return true;
        });

        var response = service.embed(10L, null, 50L);

        assertEquals(44L, response.getPipelineTaskId());
    }

    /**
     * 执行键命中 RUNNING 任务按 40431 拒绝。
     *
     * <p>守护 {@code embed}：重复建任务即失败。
     */
    @Test
    void runningShouldReject40431() {
        stubCommon();
        WbPipelineTask running = new WbPipelineTask();
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

    /**
     * 执行键命中 QUEUED 任务按 40431 拒绝。
     *
     * <p>守护 {@code embed}：重复建任务即失败。
     */
    @Test
    void existingQueuedTaskShouldReject40431() {
        stubCommon();
        WbPipelineTask queued = new WbPipelineTask();
        queued.setId(41L);
        queued.setStatus(PipelineTaskStatus.QUEUED.name());
        existingEmbedTask(50L, 66L, queued);

        // 执行键命中即拒绝，触发入口不再补投唤醒；QUEUED 补投归启动补偿与低频扫描
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.embed(10L, null, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    /**
     * 向量化详情：任务态、子步骤、产物引用与集合/记录统计逐列下发。
     *
     * <p>守护 {@code embedDetail}：漏搬任一类即失败。
     */
    @Test
    void detailShouldAssembleSummaryAndRecords() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineTask task = new WbPipelineTask();
        task.setId(41L);
        task.setStage(PipelineStage.EMBED.name());
        task.setStatus(PipelineTaskStatus.FAILED.name());
        task.setErrorCode("EMBED_TIMEOUT");
        task.setErrorMsg("模型超时");
        task.setStartedAt(java.time.LocalDateTime.of(2026, 10, 9, 10, 0));
        task.setFinishedAt(java.time.LocalDateTime.of(2026, 10, 9, 10, 5));
        task.setProductId(50L);
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.EMBED.name())).thenReturn(task);
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("abc".repeat(16));
        product.setContentHash("sha-abc");
        product.setCapabilitySnapshot("{\"model\":\"text-embedding-v4\"}");
        // 产物行记下对象位置：详情据此取向量集合
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        when(pipelineProductDbService.getById(50L)).thenReturn(product);
        com.workbuddy.common.domain.entity.WbPipelineStepLog step =
                new com.workbuddy.common.domain.entity.WbPipelineStepLog();
        step.setTaskId(41L);
        step.setStepName("encode");
        step.setStatus("SUCCESS");
        when(stepLogDbService.listByTaskId(41L)).thenReturn(List.of(step));
        WbEmbeddingSet set = new WbEmbeddingSet();
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
        WbEmbeddingRecord cached = new WbEmbeddingRecord();
        cached.setEmbeddingId("emb-0001");
        cached.setStatus("CACHED");
        cached.setCacheHit(true);
        WbEmbeddingRecord fresh = new WbEmbeddingRecord();
        fresh.setEmbeddingId("emb-0002");
        fresh.setStatus("SUCCESS");
        WbEmbeddingRecord skipped = new WbEmbeddingRecord();
        skipped.setEmbeddingId("emb-0003");
        skipped.setStatus("SKIPPED");
        when(embeddingRecordDbService.listByEmbeddingSetId(70L)).thenReturn(List.of(cached, fresh, skipped));

        EmbedDetailVO detail = service.embedDetail(10L, null);

        assertEquals(10L, detail.getFileResultId());
        assertEquals(41L, detail.getTaskId());
        assertEquals(PipelineStage.EMBED.name(), detail.getStage());
        assertEquals(PipelineTaskStatus.FAILED.name(), detail.getTaskStatus());
        assertEquals("EMBED_TIMEOUT", detail.getErrorCode());
        assertEquals("模型超时", detail.getErrorMsg());
        assertNotNull(detail.getStartedAt());
        assertNotNull(detail.getFinishedAt());
        assertEquals(1, detail.getSteps().size());
        // 产物引用与能力快照随详情下发（历史任务也能看到自己的那次配置）
        assertEquals("abc".repeat(16), detail.getArtifactId());
        assertEquals("sha-abc", detail.getContentHash());
        assertEquals("{\"model\":\"text-embedding-v4\"}", detail.getCapabilitySnapshot());
        assertNotNull(detail.getSummary());
        assertEquals(3, detail.getSummary().getRecordCount());
        assertEquals(1, detail.getSummary().getCachedCount());
        assertEquals(1, detail.getSummary().getSuccessCount());
        assertEquals(0, detail.getSummary().getFailedCount());
        assertEquals(1, detail.getSummary().getSkippedCount());
        assertEquals(3, detail.getRecords().size());
    }

    /**
     * 窗口恰好等于上限：放行（边界取等不算超）。
     *
     * <p>守护 {@code precheckWindow} 的 {@code bound > allowed} 判定：写成大于等于会误拒临界策略。
     */
    @Test
    void windowExactlyAtLimitShouldProceed() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineStrategyVersion row = embedVersion(66L, "embed-bound", "v3", RowStatus.ACTIVE.name());
        row.setConfigSnapshot("{\"model\":\"text-embedding-v4\",\"contextWindowTokens\":8192}");
        when(strategyBindingDbService.getByKbAndType(10L, EmbedStrategy.TYPE)).thenReturn(binding());
        when(strategyVersionDbService.getById(66L)).thenReturn(row);
        // text-embedding-v4 窗口 8192×1.5 = 12288；切片 softMaxLen 正好 12288 → 不超
        when(pipelineProductDbService.getById(50L))
                .thenReturn(chunkProduct("{\"routes\":{\"body\":{\"algorithm\":\"paragraph-aggregate\","
                        + "\"params\":{\"softMaxLen\":12288}}}}"));
        existingEmbedTask(50L, 66L, null);
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineTask.class).setId(44L);
            return true;
        });

        var response = service.embed(10L, null, 50L);

        assertEquals(44L, response.getPipelineTaskId());
    }

    /**
     * 上游切片产物校验：未指定 / 不存在 / 环节不符 / 归属不符都拒绝。
     *
     * <p>守护 {@code requireChunkProduct} 的四道校验：任一道放行都会把错产物当上游。
     */
    @Test
    void upstreamProductShouldBeValidated() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        stubBoundStrategy();
        KnowledgeException missing = assertThrows(KnowledgeException.class,
                () -> service.embed(10L, null, null));
        assertEquals(ErrorCode.TASK_UPSTREAM_MISSING, missing.getErrorCode());

        KnowledgeException zero = assertThrows(KnowledgeException.class,
                () -> service.embed(10L, null, 0L));
        assertEquals(ErrorCode.TASK_UPSTREAM_MISSING, zero.getErrorCode());

        when(pipelineProductDbService.getById(50L)).thenReturn(null);
        KnowledgeException notFound = assertThrows(KnowledgeException.class,
                () -> service.embed(10L, null, 50L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, notFound.getErrorCode());

        WbPipelineProduct wrongStage = chunkProduct(null);
        wrongStage.setStage(PipelineStage.PARSE.name());
        when(pipelineProductDbService.getById(50L)).thenReturn(wrongStage);
        KnowledgeException stageMismatch = assertThrows(KnowledgeException.class,
                () -> service.embed(10L, null, 50L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, stageMismatch.getErrorCode());

        WbPipelineProduct foreign = chunkProduct(null);
        foreign.setFileResultId(99L);
        when(pipelineProductDbService.getById(50L)).thenReturn(foreign);
        KnowledgeException foreignError = assertThrows(KnowledgeException.class,
                () -> service.embed(10L, null, 50L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, foreignError.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
    }

    /**
     * 向量化详情：无任务时记录为空、不读集合。
     *
     * <p>守护 {@code embedDetail}：无任务仍读集合即失败。
     */
    @Test
    void detailWithoutTaskShouldReturnEmptyRecords() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.EMBED.name())).thenReturn(null);

        EmbedDetailVO detail = service.embedDetail(10L, null);

        assertNotNull(detail.getRecords());
        assertTrue(detail.getRecords().isEmpty());
    }

    private WbPipelineStrategyVersion embedVersion(Long id, String name, String version, String status) {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(id);
        row.setType(EmbedStrategy.TYPE);
        row.setName(name);
        row.setVersion(version);
        row.setConfigSnapshot("{\"model\":\"text-embedding-v4\"}");
        row.setStatus(status);
        return row;
    }
    /**
     * 越权文件结果按 40401 拒绝。
     *
     * <p>守护 {@code embedDetail}：越权可读即失败。
     */
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
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(id);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        lenient().when(knowledgeBaseDbService.getActiveById(id)).thenReturn(kb);
    }

}
