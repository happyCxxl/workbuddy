package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.support.ChainStorageSupport;
import com.workbuddy.biz.service.support.FileResultAccessGuard;
import com.workbuddy.biz.service.support.ChunkVoAssembler;
import com.workbuddy.biz.service.support.StageStrategySupport;
import com.workbuddy.biz.service.support.TaskDetailSupport;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.biz.task.support.TaskTriggerSupport;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.entity.WbChunk;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbStrategyBinding;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.chunk.ChunkDetailVO;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.service.FileStorage;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 切片控制面服务单测：策略解析两档（显式指定 / 知识库绑定）/ 上游产物校验 / 执行键查重 /
 * 建任务快照入队 / 切片详情。
 *
 * @author cxxl
 */
@ExtendWith(MockitoExtension.class)
class ChunkControlServiceImplTest {

    /** 产物所在的数据源 */
    private static final Long SOURCE_ID = 1L;

    /** 上游产物的产出任务 ID（门禁按它的状态判定） */
    private static final Long UPSTREAM_TASK_ID = 40L;

    @Mock
    private WbFileResultDbService fileResultDbService;
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
    private WbChunkSetDbService chunkSetDbService;
    @Mock
    private WbChunkDbService chunkDbService;
    @Mock
    private WbStrategyBindingDbService strategyBindingDbService;
    @Mock
    private WbKnowledgeBaseDbService knowledgeBaseDbService;
    @Mock
    private WbSourceFileDbService sourceFileDbService;
    @Mock
    private StorageRouter storageRouter;
    @Mock
    private FileStorage fileStorage;

    private ChunkControlServiceImpl service;

    @BeforeEach
    void setUp() {
        // 触发/详情助手为纯委托类、组装器为纯映射类，用真实实例（mock 会让 VO 组装返回 null，断言失真）
        SecurityTestSupport.loginViewer();
        boundKnowledgeBase(10L);
        FileResultAccessGuard accessGuard = new FileResultAccessGuard(fileResultDbService, knowledgeBaseDbService);
        ChainStorageSupport chainStorage = new ChainStorageSupport(fileResultDbService, sourceFileDbService,
                fileStorage, storageRouter);
        service = new ChunkControlServiceImpl(pipelineProductDbService,
                new StageStrategySupport(strategyVersionDbService, strategyBindingDbService, knowledgeBaseDbService),
                new TaskTriggerSupport(pipelineTaskDbService, chainStorage, taskQueue),
                new TaskDetailSupport(pipelineTaskDbService, stepLogDbService, pipelineProductDbService),
                chunkSetDbService, chunkDbService,
                new ChunkStrategyParser(new ChunkProperties()),
                new ChunkVoAssembler(), fileStorage, accessGuard, new UpstreamProductGate(pipelineTaskDbService));
        // 上游产物的产出任务默认成功：门禁放行；不放行的状态由各用例自行改写（宽松桩）
        lenient().when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID)).thenReturn(upstreamTask(PipelineTaskStatus.SUCCESS));
    }

    /** 上游产物的产出任务（环节 PREPROCESS、归属文件结果 10） */
    private WbPipelineTask upstreamTask(PipelineTaskStatus status) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(UPSTREAM_TASK_ID);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.PREPROCESS.name());
        task.setStatus(status.name());
        return task;
    }

    private WbFileResult fileResult() {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setKnowledgeBaseId(10L);
        return fileResult;
    }

    private WbPipelineProduct preprocessProduct() {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setId(50L);
        product.setTaskId(UPSTREAM_TASK_ID);
        product.setFileResultId(10L);
        product.setStage(PipelineStage.PREPROCESS.name());
        product.setArtifactId("abc".repeat(22));
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        return product;
    }

    private void stubCommon() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(preprocessProduct());
        stubBoundStrategy();
    }

    /** 绑定档桩：KB 10 → 绑定行 → 启用中的策略版本行 66（显式指定档的用例不命中它，按宽松桩登记） */
    private void stubBoundStrategy() {
        lenient().when(strategyBindingDbService.getByKbAndType(10L, ChunkStrategy.TYPE)).thenReturn(binding());
        lenient().when(strategyVersionDbService.getById(66L)).thenReturn(chunkVersion(66L, "chunk-bound", "v3"));
    }

    /** 切片执行键检查：文件结果 10 + 上游预处理产物 + 策略版本（0 表示该环节无策略） */
    private void existingChunkTask(Long upstreamProductId, Long strategyVersionId, WbPipelineTask task) {
        when(pipelineTaskDbService.getByExecKey(10L, PipelineStage.CHUNK.name(), upstreamProductId,
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

    /** 有效绑定行：KB 10 绑定 CHUNK 策略版本 66 */
    private WbStrategyBinding binding() {
        WbStrategyBinding binding = new WbStrategyBinding();
        binding.setId(1L);
        binding.setKnowledgeBaseId(10L);
        binding.setStrategyType(ChunkStrategy.TYPE);
        binding.setStrategyVersionId(66L);
        return binding;
    }

    private WbPipelineStrategyVersion chunkVersion(Long id, String name, String version) {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(id);
        row.setType(ChunkStrategy.TYPE);
        row.setName(name);
        row.setVersion(version);
        row.setConfigSnapshot("{\"routes\":{},\"pipeline\":{\"titleInContent\":\"ON\"}}");
        row.setStatus(RowStatus.ACTIVE.name());
        return row;
    }

    @Test
    void boundStrategyShouldDriveTaskSnapshotAndEnqueue() {
        stubCommon();
        existingChunkTask(50L, 66L, null);
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineTask.class).setId(41L);
            return true;
        });

        var response = service.chunk(10L, null, 50L);

        assertEquals(41L, response.getPipelineTaskId());
        assertEquals("chunk-bound-v3", response.getStrategyVersion());
        ArgumentCaptor<WbPipelineTask> captor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(pipelineTaskDbService).save(captor.capture());
        WbPipelineTask task = captor.getValue();
        assertEquals(PipelineStage.CHUNK.name(), task.getStage());
        assertEquals(PipelineTaskStatus.QUEUED.name(), task.getStatus());
        assertEquals(50L, task.getUpstreamProductId());
        assertEquals(66L, task.getStrategyVersionId());
        assertNotNull(task.getStrategySnapshot());
        assertTrue(task.getStrategySnapshot().contains("titleInContent"));
        verify(taskQueue).enqueue(41L);
    }

    @Test
    void unboundStrategyShouldReject40438() {
        // 知识库未绑定：不再取"启用中最新"兜底，一律拒绝
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(strategyBindingDbService.getByKbAndType(10L, ChunkStrategy.TYPE)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void bindingSwitchOffShouldReject40438() {
        // 绑定开关关闭：即使库内存在绑定行也不生效
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(knowledgeBaseDbService.getActiveById(10L)).thenReturn(bindingOffKnowledgeBase());

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(strategyBindingDbService, never()).getByKbAndType(any(), any());
        verify(pipelineTaskDbService, never()).save(any());
    }

    @Test
    void missingUpstreamProductShouldThrow40436() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        stubBoundStrategy();

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, null, null));
        assertEquals(ErrorCode.TASK_UPSTREAM_MISSING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void failedUpstreamTaskShouldThrow40439() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        stubBoundStrategy();
        when(pipelineProductDbService.getById(50L)).thenReturn(preprocessProduct());
        // 产出任务已失败，产物行却已经落盘（孤儿产物）：下游门禁拒绝消费
        when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID)).thenReturn(upstreamTask(PipelineTaskStatus.FAILED));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, null, 50L));

        assertEquals(ErrorCode.TASK_UPSTREAM_NOT_READY, e.getErrorCode());
        assertTrue(e.getMessage().contains(PipelineTaskStatus.FAILED.name()), e.getMessage());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void unknownStrategyVersionShouldThrow40433() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(strategyVersionDbService.getById(999L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, 999L, 50L));
        assertEquals(ErrorCode.STRATEGY_VERSION_NOT_FOUND, e.getErrorCode());
        verify(pipelineProductDbService, never()).getByFileResultIdAndStage(any(), any());
    }

    @Test
    void explicitStrategyVersionShouldResolveFromDb() {
        stubCommon();
        when(strategyVersionDbService.getById(77L))
                .thenReturn(chunkVersion(77L, "chunk-strict", "v2"));
        existingChunkTask(50L, 77L, null);
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineTask.class).setId(42L);
            return true;
        });

        var response = service.chunk(10L, 77L, 50L);

        assertEquals("chunk-strict-v2", response.getStrategyVersion());
        ArgumentCaptor<WbPipelineTask> captor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(pipelineTaskDbService).save(captor.capture());
        assertTrue(captor.getValue().getStrategySnapshot().contains("titleInContent"));
        assertEquals(77L, captor.getValue().getStrategyVersionId());
    }

    @Test
    void boundStrategyShouldBeUsedWhenNoExplicitVersion() {
        stubCommon();
        existingChunkTask(50L, 66L, null);
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineTask.class).setId(43L);
            return true;
        });

        var response = service.chunk(10L, null, 50L);

        assertEquals("chunk-bound-v3", response.getStrategyVersion());
    }

    @Test
    void explicitStrategyShouldOverrideBinding() {
        stubCommon();
        when(strategyVersionDbService.getById(77L)).thenReturn(chunkVersion(77L, "chunk-explicit", "v4"));
        existingChunkTask(50L, 77L, null);
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineTask.class).setId(44L);
            return true;
        });

        var response = service.chunk(10L, 77L, 50L);

        assertEquals("chunk-explicit-v4", response.getStrategyVersion());
        // 显式指定时不走绑定档
        verify(strategyBindingDbService, never()).getByKbAndType(any(), any());
    }

    @Test
    void staleBindingShouldReject40438() {
        // 绑定行指向的策略版本行缺失：不再回退"启用中最新"，一律拒绝
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(knowledgeBaseDbService.getActiveById(10L)).thenReturn(knowledgeBase());
        when(strategyBindingDbService.getByKbAndType(10L, ChunkStrategy.TYPE)).thenReturn(binding());
        when(strategyVersionDbService.getById(66L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, null, 50L));

        assertEquals(ErrorCode.TASK_STRATEGY_MISSING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
    }

    @Test
    void mismatchedStrategyTypeShouldReject() {
        // 显式策略按行 id 引用，类型不匹配拒绝
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineStrategyVersion wrongType = chunkVersion(77L, "chunk-x", "v1");
        wrongType.setType("PREPROCESS");
        when(strategyVersionDbService.getById(77L)).thenReturn(wrongType);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, 77L, 50L));
        assertEquals(ErrorCode.STRATEGY_VERSION_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void runningShouldReject40431() {
        stubCommon();
        WbPipelineTask running = new WbPipelineTask();
        running.setId(41L);
        running.setStatus(PipelineTaskStatus.RUNNING.name());
        existingChunkTask(50L, 66L, running);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, null, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        // 拒绝文案带任务号与状态：用户能看出是哪条任务占着执行键
        assertTrue(e.getMessage().contains("taskId=41"), e.getMessage());
        assertTrue(e.getMessage().contains(PipelineTaskStatus.RUNNING.name()), e.getMessage());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void existingQueuedTaskShouldReject40431() {
        stubCommon();
        WbPipelineTask queued = new WbPipelineTask();
        queued.setId(41L);
        queued.setStatus(PipelineTaskStatus.QUEUED.name());
        existingChunkTask(50L, 66L, queued);

        // 执行键命中即拒绝，触发入口不再补投唤醒；QUEUED 补投归启动补偿与低频扫描
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, null, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void detailShouldAssembleChunkSetSummaryAndChunks() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineTask task = new WbPipelineTask();
        task.setId(41L);
        task.setStage(PipelineStage.CHUNK.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.CHUNK.name())).thenReturn(task);
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("abc".repeat(16));
        // 产物行记下对象位置：详情按它取切片集合
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        when(pipelineProductDbService.getById(50L)).thenReturn(product);
        WbChunkSet chunkSet = new WbChunkSet();
        chunkSet.setId(70L);
        chunkSet.setChunkCount(2);
        chunkSet.setTotalChars(300);
        chunkSet.setArtifactId("abc".repeat(16));
        chunkSet.setStorageType(StorageType.MINIO.getCode());
        chunkSet.setStorageSourceId(SOURCE_ID);
        chunkSet.setBucket("artifacts");
        when(chunkSetDbService.getByArtifactId("abc".repeat(16))).thenReturn(chunkSet);
        WbChunk parent = new WbChunk();
        parent.setChunkId("c-1");
        parent.setContentType("SECTION");
        parent.setContent("章节父片");
        parent.setTitlePath("第一章");
        parent.setOrderNo(1);
        parent.setCharCount(200);
        WbChunk child = new WbChunk();
        child.setChunkId("c-1-1");
        child.setParentChunkId("c-1");
        child.setContentType("TABLE");
        child.setContent("表格子片");
        child.setOrderNo(2);
        child.setCharCount(100);
        when(chunkDbService.listByChunkSetId(70L)).thenReturn(List.of(parent, child));

        ChunkDetailVO detail = service.chunkDetail(10L, null);

        assertEquals(41L, detail.getTaskId());
        assertEquals(PipelineTaskStatus.SUCCESS.name(), detail.getStatus());
        assertNotNull(detail.getSummary());
        assertEquals(2, detail.getSummary().getChunkCount());
        assertEquals(300, detail.getSummary().getTotalChars());
        assertEquals(150, detail.getSummary().getAvgChars());
        assertEquals(1, detail.getSummary().getParentChunkCount());
        assertEquals(1, detail.getSummary().getChildChunkCount());
        assertEquals(2, detail.getSummary().getTypeCounts().size());
        assertEquals(2, detail.getChunks().size());
        assertEquals("c-1-1", detail.getChunks().get(1).getChunkId());
        assertEquals("c-1", detail.getChunks().get(1).getParentChunkId());
    }

    @Test
    void detailWithValidStrategySnapshotShouldExposeStrategyStats() {
        stubDetail(taskWithStrategySnapshot("{\"name\":\"chunk-hybrid\",\"version\":\"v1\"}"));

        ChunkDetailVO detail = service.chunkDetail(10L, null);

        Map<String, Object> stats = detail.getStageStats();
        assertNotNull(stats);
        assertEquals(1, stats.get("chunkCount"));
        // 策略可解析：片长上限与兜底参数照常下发
        assertTrue(stats.containsKey("targetMaxLen"), String.valueOf(stats));
        assertTrue(stats.containsKey("softMaxLen"), String.valueOf(stats));
        assertTrue(stats.containsKey("fallbackLen"), String.valueOf(stats));
    }

    @Test
    void detailWithCorruptStrategySnapshotShouldLeaveStrategyStatsEmpty() {
        stubDetail(taskWithStrategySnapshot("{oops"));

        ChunkDetailVO detail = service.chunkDetail(10L, null);

        Map<String, Object> stats = detail.getStageStats();
        assertNotNull(stats);
        assertEquals(1, stats.get("chunkCount"));
        // 策略快照损坏：策略类口径整组留空（页面上如实表现为无策略快照），不炸也不造默认
        assertFalse(stats.containsKey("targetMaxLen"), String.valueOf(stats));
        assertFalse(stats.containsKey("softMaxLen"), String.valueOf(stats));
        assertFalse(stats.containsKey("fallbackLen"), String.valueOf(stats));
        assertFalse(stats.containsKey("minMergeLen"), String.valueOf(stats));
    }

    /** 详情用例共用桩：任务行 + 产物行 + 产物本体 + 集合行 + 一条切片；strategySnapshot 由用例指定 */
    private void stubDetail(WbPipelineTask task) {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.CHUNK.name())).thenReturn(task);
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("abc".repeat(16));
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        when(pipelineProductDbService.getById(50L)).thenReturn(product);
        // 统计与摘要读产物现算：产物本体缺失时统计不下发，用例必须给本体
        ChunkSet body = new ChunkSet();
        body.setChunkSetId("cs-doc-10-chunk-hybrid-v1");
        Chunk chunk = new Chunk();
        chunk.setChunkId("c-1");
        chunk.setContentType("PARAGRAPH");
        chunk.setContent("正文片");
        chunk.setCharCount(3);
        body.setChunks(List.of(chunk));
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "abc".repeat(16))))
                .thenReturn(Objects.requireNonNull(JsonUtil.toJsonStr(body)).getBytes(StandardCharsets.UTF_8));
        WbChunkSet chunkSet = new WbChunkSet();
        chunkSet.setId(70L);
        chunkSet.setChunkCount(1);
        chunkSet.setArtifactId("abc".repeat(16));
        chunkSet.setStorageType(StorageType.MINIO.getCode());
        chunkSet.setStorageSourceId(SOURCE_ID);
        chunkSet.setBucket("artifacts");
        when(chunkSetDbService.getByArtifactId("abc".repeat(16))).thenReturn(chunkSet);
        WbChunk child = new WbChunk();
        child.setChunkId("c-1");
        child.setContentType("PARAGRAPH");
        child.setContent("正文片");
        child.setOrderNo(1);
        child.setCharCount(3);
        when(chunkDbService.listByChunkSetId(70L)).thenReturn(List.of(child));
    }

    private WbPipelineTask taskWithStrategySnapshot(String snapshot) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(41L);
        task.setStage(PipelineStage.CHUNK.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        task.setStrategySnapshot(snapshot);
        return task;
    }

    @Test
    void detailWithoutTaskShouldReturnEmptyChunks() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.CHUNK.name())).thenReturn(null);

        ChunkDetailVO detail = service.chunkDetail(10L, null);

        assertNotNull(detail.getChunks());
        assertTrue(detail.getChunks().isEmpty());
    }

    @Test
    void detailWithWrongStageTaskShouldReject() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineTask preprocessTask = new WbPipelineTask();
        preprocessTask.setId(31L);
        preprocessTask.setFileResultId(10L);
        preprocessTask.setStage(PipelineStage.PREPROCESS.name());
        when(pipelineTaskDbService.getById(31L)).thenReturn(preprocessTask);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunkDetail(10L, 31L));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    @Test
    void detailWithIncompleteProductLocationShouldReject40455() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineTask task = new WbPipelineTask();
        task.setId(41L);
        task.setStage(PipelineStage.CHUNK.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.CHUNK.name())).thenReturn(task);
        // 产物行缺 storageType / storageSourceId / bucket：位置不完整，详情按「存储后端未配置」拒绝
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("abc".repeat(16));
        when(pipelineProductDbService.getById(50L)).thenReturn(product);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunkDetail(10L, null));

        assertEquals(ErrorCode.STORAGE_BACKEND_UNCONFIGURED, e.getErrorCode());
        verify(chunkSetDbService, never()).getByArtifactId(any());
    }

    @Test
    void otherUserFileResultShouldReject40401() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        // 归属看的是知识库归属：换一个登录用户，越权与"不存在"同样返回 40401
        SecurityTestSupport.loginOtherUser();

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.chunk(10L, null, 50L));
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
