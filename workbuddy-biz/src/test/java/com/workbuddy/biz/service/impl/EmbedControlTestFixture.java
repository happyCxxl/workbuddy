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
import com.workbuddy.biz.service.support.access.ChainStorageSupport;
import com.workbuddy.biz.service.support.access.FileResultAccessGuard;
import com.workbuddy.biz.service.support.common.StageStrategySupport;
import com.workbuddy.biz.service.support.common.TaskDetailSupport;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.biz.task.support.TaskTriggerSupport;
import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbStrategyBinding;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import com.workbuddy.worker.embedding.strategy.EmbedStrategy;
import com.workbuddy.worker.embedding.strategy.EmbedStrategyParser;
import com.workbuddy.common.convert.knowledge.EmbedConverterImpl;

import com.workbuddy.biz.config.ConfigFixtures;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 向量化控制面测试夹具：装配被测服务（真实助手 + 边界替身）、共享数据源与上游任务桩、常用实体。
 *
 * <p>触发与详情两组用例共用同一份装配与夹具数据；各测试类不再重复声明依赖。
 */
final class EmbedControlTestFixture {

    /** 产物所在的数据源 */
    static final Long SOURCE_ID = 1L;

    /** 上游产物的产出任务 ID（门禁按它的状态判定） */
    static final Long UPSTREAM_TASK_ID = 40L;

    final WbFileResultDbService fileResultDbService = mock(WbFileResultDbService.class);
    final WbKnowledgeBaseDbService knowledgeBaseDbService = mock(WbKnowledgeBaseDbService.class);
    final WbPipelineTaskDbService pipelineTaskDbService = mock(WbPipelineTaskDbService.class);
    final WbPipelineProductDbService pipelineProductDbService = mock(WbPipelineProductDbService.class);
    final WbPipelineStrategyVersionDbService strategyVersionDbService =
            mock(WbPipelineStrategyVersionDbService.class);
    final WbPipelineStepLogDbService stepLogDbService = mock(WbPipelineStepLogDbService.class);
    final TaskQueueSupport taskQueue = mock(TaskQueueSupport.class);
    final WbEmbeddingSetDbService embeddingSetDbService = mock(WbEmbeddingSetDbService.class);
    final WbEmbeddingRecordDbService embeddingRecordDbService = mock(WbEmbeddingRecordDbService.class);
    final WbStrategyBindingDbService strategyBindingDbService = mock(WbStrategyBindingDbService.class);
    final WbSourceFileDbService sourceFileDbService = mock(WbSourceFileDbService.class);
    final StorageRouter storageRouter = mock(StorageRouter.class);
    final FileStorage fileStorage = mock(FileStorage.class);

    private final EmbedControlServiceImpl service;

    EmbedControlTestFixture() {
        // 触发/详情助手为纯委托类、组装器为纯映射类，用真实实例（mock 会让 VO 组装返回 null，断言失真）
        SecurityTestSupport.loginViewer();
        boundKnowledgeBase(10L);
        FileResultAccessGuard accessGuard = new FileResultAccessGuard(fileResultDbService, knowledgeBaseDbService);
        ChainStorageSupport chainStorage = new ChainStorageSupport(fileResultDbService, sourceFileDbService,
                fileStorage, storageRouter);
        service = new EmbedControlServiceImpl(pipelineProductDbService, stepLogDbService,
                embeddingSetDbService, embeddingRecordDbService, new EmbedConverterImpl(),
                new EmbedStrategyParser(ConfigFixtures.embed()),
                new ChunkStrategyParser(ConfigFixtures.chunk()),
                ConfigFixtures.embed(), ConfigFixtures.chunk(),
                new StageStrategySupport(strategyVersionDbService, strategyBindingDbService, knowledgeBaseDbService),
                new TaskTriggerSupport(pipelineTaskDbService, chainStorage, taskQueue),
                new TaskDetailSupport(pipelineTaskDbService, stepLogDbService, pipelineProductDbService), accessGuard,
                new UpstreamProductGate(pipelineTaskDbService));
        // 上游产物的产出任务默认成功：门禁放行；不放行的状态由各用例自行改写（宽松桩）
        lenient().when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID))
                .thenReturn(upstreamTask(PipelineTaskStatus.SUCCESS));
    }

    /** 被测服务（真实助手实例） */
    EmbedControlServiceImpl service() {
        return service;
    }

    /** 上游产物的产出任务（环节 CHUNK、归属文件结果 10） */
    WbPipelineTask upstreamTask(PipelineTaskStatus status) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(UPSTREAM_TASK_ID);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.CHUNK.name());
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

    /** CHUNK 产物（capabilitySnapshot 可指定切片策略快照；空=跳过窗口预检） */
    WbPipelineProduct chunkProduct(String capabilitySnapshot) {
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

    /** 触发用例共用桩：文件结果 + 上游产物 + 绑定策略档 */
    void stubCommon() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(chunkProduct(null));
        stubBoundStrategy();
    }

    /** 绑定档桩：KB 10 → 绑定行 → 启用中的策略版本行 66（显式指定档的用例不命中它，按宽松桩登记） */
    void stubBoundStrategy() {
        lenient().when(strategyBindingDbService.getByKbAndType(10L, EmbedStrategy.TYPE)).thenReturn(binding());
        lenient().when(strategyVersionDbService.getById(66L))
                .thenReturn(embedVersion(66L, "embed-bound", "v3", RowStatus.ACTIVE.name()));
    }

    /** 向量化执行键检查：文件结果 10 + 上游切片产物 + 策略版本（0 表示该环节无策略） */
    void existingEmbedTask(Long upstreamProductId, Long strategyVersionId, WbPipelineTask task) {
        when(pipelineTaskDbService.getByExecKey(10L, PipelineStage.EMBED.name(), upstreamProductId,
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

    /** 有效绑定行：KB 10 绑定 EMBED 策略版本 66 */
    WbStrategyBinding binding() {
        WbStrategyBinding binding = new WbStrategyBinding();
        binding.setId(1L);
        binding.setKnowledgeBaseId(10L);
        binding.setStrategyType(EmbedStrategy.TYPE);
        binding.setStrategyVersionId(66L);
        return binding;
    }

    /** EMBED 策略版本行夹具（指定启用状态） */
    WbPipelineStrategyVersion embedVersion(Long id, String name, String version, String status) {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(id);
        row.setType(EmbedStrategy.TYPE);
        row.setName(name);
        row.setVersion(version);
        row.setConfigSnapshot("{\"model\":\"text-embedding-v4\"}");
        row.setStatus(status);
        return row;
    }

    /** 让指定知识库归当前登录用户所有（归属校验要能过） */
    private void boundKnowledgeBase(Long id) {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(id);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        lenient().when(knowledgeBaseDbService.getActiveById(id)).thenReturn(kb);
    }
}
