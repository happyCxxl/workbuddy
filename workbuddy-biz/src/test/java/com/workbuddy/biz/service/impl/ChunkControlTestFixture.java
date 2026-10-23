package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.support.access.ChainStorageSupport;
import com.workbuddy.biz.service.support.access.FileResultAccessGuard;
import com.workbuddy.biz.service.support.strategy.StageStrategySupport;
import com.workbuddy.biz.service.support.task.TaskDetailSupport;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.biz.task.support.TaskTriggerSupport;
import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.convert.knowledge.ChunkConverterImpl;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.entity.WbChunk;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbStrategyBinding;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.workbuddy.biz.config.ConfigFixtures;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 切片控制面测试夹具：装配被测服务（真实助手 + 边界替身）、共享数据源与上游任务桩、常用实体。
 *
 * <p>触发与详情两组用例共用同一份装配与夹具数据；各测试类不再重复声明依赖。
 */
final class ChunkControlTestFixture {

    /** 产物所在的数据源 */
    static final Long SOURCE_ID = 1L;

    /** 上游产物的产出任务 ID（门禁按它的状态判定） */
    static final Long UPSTREAM_TASK_ID = 40L;

    final WbFileResultDbService fileResultDbService = mock(WbFileResultDbService.class);
    final WbPipelineTaskDbService pipelineTaskDbService = mock(WbPipelineTaskDbService.class);
    final WbPipelineProductDbService pipelineProductDbService = mock(WbPipelineProductDbService.class);
    final WbPipelineStrategyVersionDbService strategyVersionDbService =
            mock(WbPipelineStrategyVersionDbService.class);
    final WbPipelineStepLogDbService stepLogDbService = mock(WbPipelineStepLogDbService.class);
    final TaskQueueSupport taskQueue = mock(TaskQueueSupport.class);
    final WbChunkSetDbService chunkSetDbService = mock(WbChunkSetDbService.class);
    final WbChunkDbService chunkDbService = mock(WbChunkDbService.class);
    final WbStrategyBindingDbService strategyBindingDbService = mock(WbStrategyBindingDbService.class);
    final WbKnowledgeBaseDbService knowledgeBaseDbService = mock(WbKnowledgeBaseDbService.class);
    final WbSourceFileDbService sourceFileDbService = mock(WbSourceFileDbService.class);
    final StorageRouter storageRouter = mock(StorageRouter.class);
    final FileStorage fileStorage = mock(FileStorage.class);

    private final ChunkControlServiceImpl service;

    ChunkControlTestFixture() {
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
                new ChunkStrategyParser(ConfigFixtures.chunk()),
                new ChunkConverterImpl(), fileStorage, accessGuard, new UpstreamProductGate(pipelineTaskDbService));
        // 上游产物的产出任务默认成功：门禁放行；不放行的状态由各用例自行改写（宽松桩）
        lenient().when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID))
                .thenReturn(upstreamTask(PipelineTaskStatus.SUCCESS));
    }

    /** 被测服务（真实助手实例） */
    ChunkControlServiceImpl service() {
        return service;
    }

    /** 上游产物的产出任务（环节 PREPROCESS、归属文件结果 10） */
    WbPipelineTask upstreamTask(PipelineTaskStatus status) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(UPSTREAM_TASK_ID);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.PREPROCESS.name());
        task.setStatus(status.name());
        return task;
    }

    /** 文件结果行夹具（归属知识库 10） */
    WbFileResult fileResult() {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setKnowledgeBaseId(10L);
        return fileResult;
    }

    /** 上游预处理产物夹具（对象位置齐全） */
    WbPipelineProduct preprocessProduct() {
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

    /** 触发用例共用桩：文件结果 + 上游产物 + 绑定策略档 */
    void stubCommon() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(preprocessProduct());
        stubBoundStrategy();
    }

    /** 绑定档桩：KB 10 → 绑定行 → 启用中的策略版本行 66（显式指定档的用例不命中它，按宽松桩登记） */
    void stubBoundStrategy() {
        lenient().when(strategyBindingDbService.getByKbAndType(10L, ChunkStrategy.TYPE)).thenReturn(binding());
        lenient().when(strategyVersionDbService.getById(66L)).thenReturn(chunkVersion(66L, "chunk-bound", "v3"));
    }

    /** 切片执行键检查：文件结果 10 + 上游预处理产物 + 策略版本（0 表示该环节无策略） */
    void existingChunkTask(Long upstreamProductId, Long strategyVersionId, WbPipelineTask task) {
        when(pipelineTaskDbService.getByExecKey(10L, PipelineStage.CHUNK.name(), upstreamProductId,
                strategyVersionId)).thenReturn(task);
    }

    /** 任务落库桩：回填指定任务号（触发成功路径共用） */
    void stubSaveAssignsId(long taskId) {
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineTask.class).setId(taskId);
            return true;
        });
    }

    /** 知识库夹具（绑定开关未显式关闭 → 视为开启） */
    WbKnowledgeBase knowledgeBase() {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(10L);
        kb.setStrategyBindingEnabled(1);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        return kb;
    }

    /** 绑定开关关闭的知识库 */
    WbKnowledgeBase bindingOffKnowledgeBase() {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(10L);
        kb.setStrategyBindingEnabled(0);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        return kb;
    }

    /** 有效绑定行：KB 10 绑定 CHUNK 策略版本 66 */
    WbStrategyBinding binding() {
        WbStrategyBinding binding = new WbStrategyBinding();
        binding.setId(1L);
        binding.setKnowledgeBaseId(10L);
        binding.setStrategyType(ChunkStrategy.TYPE);
        binding.setStrategyVersionId(66L);
        return binding;
    }

    /** CHUNK 策略版本行夹具（启用中） */
    WbPipelineStrategyVersion chunkVersion(Long id, String name, String version) {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(id);
        row.setType(ChunkStrategy.TYPE);
        row.setName(name);
        row.setVersion(version);
        row.setConfigSnapshot("{\"routes\":{},\"pipeline\":{\"titleInContent\":\"ON\"}}");
        row.setStatus(RowStatus.ACTIVE.name());
        return row;
    }

    /** 产物行夹具（只填对象位置） */
    WbPipelineProduct productOf(String artifactId) {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId(artifactId);
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        return product;
    }

    /** 产物内容夹具：按产物行的对象位置写 JSON 字节 */
    void stubArtifact(WbPipelineProduct product, Object value) {
        when(fileStorage.getObject(Objects.requireNonNull(ObjectRef.ofProduct(product))))
                .thenReturn(Objects.requireNonNull(JsonUtil.toJsonStr(value)).getBytes(StandardCharsets.UTF_8));
    }

    /** 切片集合产物夹具 */
    ChunkSet chunkSetOf(Chunk... chunks) {
        ChunkSet chunkSet = new ChunkSet();
        chunkSet.setChunks(new ArrayList<>(List.of(chunks)));
        return chunkSet;
    }

    /** 产物里的片夹具（来源元素与兜底原因） */
    Chunk chunk(String chunkId, List<String> sources, String fallbackReason) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkId);
        chunk.setContentType("PARAGRAPH");
        chunk.setContent("正文");
        chunk.setCharCount(10);
        chunk.setSourceElementIds(new ArrayList<>(sources));
        chunk.setFallbackReason(fallbackReason);
        return chunk;
    }

    /** 详情用例共用桩：任务行 + 产物行 + 产物本体 + 集合行 + 一条切片；strategySnapshot 由用例指定 */
    void stubDetail(WbPipelineTask task) {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.CHUNK.name())).thenReturn(task);
        WbPipelineProduct product = productOf("abc".repeat(16));
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
        stubArtifact(product, body);
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

    /** 成功任务夹具：指定策略快照，产物为 50 */
    WbPipelineTask taskWithStrategySnapshot(String snapshot) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(41L);
        task.setStage(PipelineStage.CHUNK.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        task.setStrategySnapshot(snapshot);
        return task;
    }

    /** 让指定知识库归当前登录用户所有（归属校验要能过） */
    private void boundKnowledgeBase(Long id) {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(id);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        lenient().when(knowledgeBaseDbService.getActiveById(id)).thenReturn(kb);
    }
}
