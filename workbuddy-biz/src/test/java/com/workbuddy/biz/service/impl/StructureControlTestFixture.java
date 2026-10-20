package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.support.access.ChainStorageSupport;
import com.workbuddy.biz.service.support.access.FileResultAccessGuard;
import com.workbuddy.biz.service.support.assemble.StructureVoAssembler;
import com.workbuddy.biz.service.support.common.TaskDetailSupport;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.biz.task.support.TaskTriggerSupport;
import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 结构组装控制面测试夹具：装配被测服务（真实助手 + 边界替身）、共享上游任务桩与常用实体。
 *
 * <p>触发与详情两组用例共用同一份装配与夹具数据；各测试类不再重复声明依赖。
 */
final class StructureControlTestFixture {

    /** 档案与产物所在的数据源 */
    static final Long SOURCE_ID = 1L;

    /** 上游产物的产出任务 ID（门禁按它的状态判定） */
    static final Long UPSTREAM_TASK_ID = 40L;

    final WbFileResultDbService fileResultDbService = mock(WbFileResultDbService.class);
    final WbPipelineTaskDbService pipelineTaskDbService = mock(WbPipelineTaskDbService.class);
    final WbPipelineProductDbService pipelineProductDbService = mock(WbPipelineProductDbService.class);
    final WbPipelineStepLogDbService stepLogDbService = mock(WbPipelineStepLogDbService.class);
    final TaskQueueSupport taskQueue = mock(TaskQueueSupport.class);
    final WbKnowledgeBaseDbService knowledgeBaseDbService = mock(WbKnowledgeBaseDbService.class);
    final WbSourceFileDbService sourceFileDbService = mock(WbSourceFileDbService.class);
    final StorageRouter storageRouter = mock(StorageRouter.class);
    final FileStorage fileStorage = mock(FileStorage.class);

    private final StructureControlServiceImpl service;

    StructureControlTestFixture() {
        // 触发/详情助手为纯委托类、组装器为纯映射类，用真实实例（mock 会让 VO 组装返回 null，断言失真）
        SecurityTestSupport.loginViewer();
        boundKnowledgeBase(10L);
        FileResultAccessGuard accessGuard = new FileResultAccessGuard(fileResultDbService, knowledgeBaseDbService);
        ChainStorageSupport chainStorage = new ChainStorageSupport(fileResultDbService, sourceFileDbService,
                fileStorage, storageRouter);
        service = new StructureControlServiceImpl(pipelineProductDbService,
                new TaskTriggerSupport(pipelineTaskDbService, chainStorage, taskQueue),
                new TaskDetailSupport(pipelineTaskDbService, stepLogDbService, pipelineProductDbService), fileStorage,
                new StructureVoAssembler(), accessGuard, new UpstreamProductGate(pipelineTaskDbService));
        // 上游产物的产出任务默认成功：门禁放行；不放行的状态由各用例自行改写（宽松桩）
        lenient().when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID))
                .thenReturn(upstreamTask(PipelineTaskStatus.SUCCESS));
    }

    /** 被测服务（真实助手实例） */
    StructureControlServiceImpl service() {
        return service;
    }

    /** 上游产物的产出任务（环节 PARSE、归属文件结果 10） */
    WbPipelineTask upstreamTask(PipelineTaskStatus status) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(UPSTREAM_TASK_ID);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.PARSE.name());
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

    /** 上游解析产物夹具（id 50，归属文件结果 10） */
    WbPipelineProduct parseProduct() {
        return parseProduct(50L);
    }

    /** 指定 id 的上游解析产物夹具 */
    WbPipelineProduct parseProduct(Long id) {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setId(id);
        product.setTaskId(UPSTREAM_TASK_ID);
        product.setFileResultId(10L);
        product.setStage(PipelineStage.PARSE.name());
        return product;
    }

    /** 指定状态的任务夹具（执行键命中场景） */
    WbPipelineTask taskWithStatus(Long id, PipelineTaskStatus status) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(id);
        task.setStatus(status.name());
        return task;
    }

    /** 组装执行键命中检查：文件结果 10 + 上游解析产物 + 无策略（0） */
    void existingStructureTask(Long upstreamProductId, WbPipelineTask task) {
        when(pipelineTaskDbService.getByExecKey(10L, PipelineStage.STRUCTURE.name(), upstreamProductId, 0L))
                .thenReturn(task);
    }

    /** 任务落库桩：回填指定任务号（触发成功路径共用） */
    void stubSaveAssignsId(long taskId) {
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineTask.class).setId(taskId);
            return true;
        });
    }

    /** 让指定知识库归当前登录用户所有（归属校验要能过） */
    private void boundKnowledgeBase(Long id) {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(id);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        lenient().when(knowledgeBaseDbService.getActiveById(id)).thenReturn(kb);
    }
}
