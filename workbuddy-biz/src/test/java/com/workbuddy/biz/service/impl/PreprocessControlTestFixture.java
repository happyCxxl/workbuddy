package com.workbuddy.biz.service.impl;

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
import com.workbuddy.biz.service.support.PreprocessVoAssembler;
import com.workbuddy.biz.service.support.StageStrategySupport;
import com.workbuddy.biz.service.support.TaskDetailSupport;
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
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.preprocessing.PreprocessProperties;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategyParser;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 预处理控制面测试夹具：装配被测服务（真实助手 + 边界替身）、共享数据源与上游任务桩、常用实体。
 *
 * <p>触发与详情两组用例共用同一份装配与夹具数据；各测试类不再重复声明依赖。
 */
final class PreprocessControlTestFixture {

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
    final FileStorage fileStorage = mock(FileStorage.class);
    final WbSourceFileDbService sourceFileDbService = mock(WbSourceFileDbService.class);
    final StorageRouter storageRouter = mock(StorageRouter.class);
    final WbStrategyBindingDbService strategyBindingDbService = mock(WbStrategyBindingDbService.class);
    final WbKnowledgeBaseDbService knowledgeBaseDbService = mock(WbKnowledgeBaseDbService.class);

    private final PreprocessControlServiceImpl service;

    PreprocessControlTestFixture() {
        // 触发/详情助手为纯委托类、组装器为纯映射类，用真实实例（mock 会让 VO 组装返回 null，断言失真）
        SecurityTestSupport.loginViewer();
        boundKnowledgeBase(10L);
        FileResultAccessGuard accessGuard = new FileResultAccessGuard(fileResultDbService, knowledgeBaseDbService);
        ChainStorageSupport chainStorage = new ChainStorageSupport(fileResultDbService, sourceFileDbService,
                fileStorage, storageRouter);
        service = new PreprocessControlServiceImpl(pipelineProductDbService,
                new StageStrategySupport(strategyVersionDbService, strategyBindingDbService, knowledgeBaseDbService),
                new TaskTriggerSupport(pipelineTaskDbService, chainStorage, taskQueue),
                new TaskDetailSupport(pipelineTaskDbService, stepLogDbService, pipelineProductDbService), fileStorage,
                new PreprocessStrategyParser(new PreprocessProperties()),
                new PreprocessVoAssembler(), accessGuard, new UpstreamProductGate(pipelineTaskDbService));
        // 上游产物的产出任务默认成功：门禁放行；不放行的状态由各用例自行改写（宽松桩）
        lenient().when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID))
                .thenReturn(upstreamTask(PipelineTaskStatus.SUCCESS));
    }

    /** 被测服务（真实助手实例） */
    PreprocessControlServiceImpl service() {
        return service;
    }

    /** 上游产物的产出任务（环节 STRUCTURE、归属文件结果 10） */
    WbPipelineTask upstreamTask(PipelineTaskStatus status) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(UPSTREAM_TASK_ID);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.STRUCTURE.name());
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

    /** 上游统一结构产物夹具 */
    WbPipelineProduct structureProduct() {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setId(50L);
        product.setTaskId(UPSTREAM_TASK_ID);
        product.setFileResultId(10L);
        product.setStage(PipelineStage.STRUCTURE.name());
        return product;
    }

    /** 指定 id 的上游统一结构产物夹具 */
    WbPipelineProduct structureProduct(Long id) {
        WbPipelineProduct product = structureProduct();
        product.setId(id);
        return product;
    }

    /** 触发用例共用桩：文件结果 + 上游产物 + 绑定策略档 */
    void stubCommon() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineProductDbService.getById(50L)).thenReturn(structureProduct());
        stubBoundStrategy();
    }

    /** 绑定档桩：KB 10 → 绑定行 → 启用中的策略版本行 66（显式指定档的用例不命中它，按宽松桩登记） */
    void stubBoundStrategy() {
        lenient().when(strategyBindingDbService.getByKbAndType(10L, PreprocessStrategy.TYPE)).thenReturn(binding());
        lenient().when(strategyVersionDbService.getById(66L)).thenReturn(boundVersion());
    }

    /** 预处理执行键检查：文件结果 10 + 上游组装产物 + 策略版本（0 表示该环节无策略） */
    void existingPreprocessTask(Long upstreamProductId, Long strategyVersionId, WbPipelineTask task) {
        when(pipelineTaskDbService.getByExecKey(10L, PipelineStage.PREPROCESS.name(), upstreamProductId,
                strategyVersionId)).thenReturn(task);
    }

    /** 绑定档查询桩：知识库归属 + 绑定行 + 指定版本行（null 表示版本缺失） */
    void stubBoundVersion(WbPipelineStrategyVersion version) {
        when(knowledgeBaseDbService.getActiveById(10L)).thenReturn(knowledgeBase());
        when(strategyBindingDbService.getByKbAndType(10L, PreprocessStrategy.TYPE)).thenReturn(binding());
        when(strategyVersionDbService.getById(66L)).thenReturn(version);
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

    /** 有效绑定行：KB 10 绑定 PREPROCESS 策略版本 66 */
    WbStrategyBinding binding() {
        WbStrategyBinding binding = new WbStrategyBinding();
        binding.setId(1L);
        binding.setKnowledgeBaseId(10L);
        binding.setStrategyType(PreprocessStrategy.TYPE);
        binding.setStrategyVersionId(66L);
        return binding;
    }

    /** 绑定行指向的策略版本行（启用中） */
    WbPipelineStrategyVersion boundVersion() {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(66L);
        row.setType(PreprocessStrategy.TYPE);
        row.setName("preproc-bound");
        row.setVersion("v3");
        row.setStatus("ACTIVE");
        row.setConfigSnapshot("{\"rules\":{\"headerFooter\":{\"action\":\"KEEP\"}}}");
        return row;
    }

    /** 显式指定的 PREPROCESS 策略版本行夹具 */
    WbPipelineStrategyVersion preprocessVersion(Long id, String name, String version, String configSnapshot) {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(id);
        row.setType(PreprocessStrategy.TYPE);
        row.setName(name);
        row.setVersion(version);
        row.setStatus(RowStatus.ACTIVE.name());
        row.setConfigSnapshot(configSnapshot);
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
