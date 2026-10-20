package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.support.ChainStorageSupport;
import com.workbuddy.biz.service.support.FileResultAccessGuard;
import com.workbuddy.biz.service.support.StructureVoAssembler;
import com.workbuddy.biz.service.support.TaskDetailSupport;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.biz.task.support.TaskTriggerSupport;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.structure.StructureDetailVO;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
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
 * 结构组装控制面服务单测：上游产物校验 / 防重 / 建任务入队（upstream 链）/ 组装详情。
 */
@ExtendWith(MockitoExtension.class)
class StructureControlServiceImplTest {

    /** 档案与产物所在的数据源 */
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
    private WbPipelineStepLogDbService stepLogDbService;
    @Mock
    private TaskQueueSupport taskQueue;
    @Mock
    private WbKnowledgeBaseDbService knowledgeBaseDbService;
    @Mock
    private WbSourceFileDbService sourceFileDbService;
    @Mock
    private StorageRouter storageRouter;
    @Mock
    private FileStorage fileStorage;

    private StructureControlServiceImpl service;

    @BeforeEach
    void setUp() {
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

    /** 上游产物的产出任务（环节 PARSE、归属文件结果 10） */
    private WbPipelineTask upstreamTask(PipelineTaskStatus status) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(UPSTREAM_TASK_ID);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.PARSE.name());
        task.setStatus(status.name());
        return task;
    }

    private WbFileResult fileResult() {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setKnowledgeBaseId(10L);
        return fileResult;
    }

    /** 组装执行键命中检查：文件结果 10 + 上游解析产物 + 无策略（0） */
    private void existingStructureTask(Long upstreamProductId, WbPipelineTask task) {
        when(pipelineTaskDbService.getByExecKey(10L, PipelineStage.STRUCTURE.name(), upstreamProductId, 0L))
                .thenReturn(task);
    }

    /**
     * 组装：以上游解析产物建任务并入队。
     *
     * <p>守护 {@code structure}：上游产物或任务字段错即失败。
     */
    @Test
    void structureShouldCreateTaskWithUpstream() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineProduct parseProduct = new WbPipelineProduct();
        parseProduct.setId(50L);
        parseProduct.setTaskId(UPSTREAM_TASK_ID);
        parseProduct.setFileResultId(10L);
        parseProduct.setStage(PipelineStage.PARSE.name());
        parseProduct.setArtifactId("abc");
        when(pipelineProductDbService.getById(50L)).thenReturn(parseProduct);
        existingStructureTask(50L, null);
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineTask.class).setId(51L);
            return true;
        });

        var response = service.structure(10L, 50L);

        assertEquals(51L, response.getPipelineTaskId());
        ArgumentCaptor<WbPipelineTask> captor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(pipelineTaskDbService).save(captor.capture());
        assertEquals(PipelineStage.STRUCTURE.name(), captor.getValue().getStage());
        assertEquals(50L, captor.getValue().getUpstreamProductId());
        // 组装无策略：任务行的策略位落 0 哨兵
        assertEquals(0L, captor.getValue().getStrategyVersionId());
        verify(taskQueue).enqueue(51L);
    }

    /**
     * 组装：显式指定上游产物优先。
     *
     * <p>守护 {@code structure}：显式档被覆盖即失败。
     */
    @Test
    void structureShouldUseSpecifiedUpstreamProduct() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineProduct specified = new WbPipelineProduct();
        specified.setId(88L);
        specified.setTaskId(UPSTREAM_TASK_ID);
        specified.setFileResultId(10L);
        specified.setStage(PipelineStage.PARSE.name());
        // 指定路径：按 id 查产物并校验
        when(pipelineProductDbService.getById(88L)).thenReturn(specified);
        existingStructureTask(88L, null);
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineTask.class).setId(89L);
            return true;
        });

        var response = service.structure(10L, 88L);

        assertEquals(89L, response.getPipelineTaskId());
        ArgumentCaptor<WbPipelineTask> captor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(pipelineTaskDbService).save(captor.capture());
        assertEquals(88L, captor.getValue().getUpstreamProductId());
    }

    /**
     * 组装：上游产物环节不符按 40404 拒绝。
     *
     * <p>守护 {@code structure}：错环节产物被消费即失败。
     */
    @Test
    void structureWithMismatchedProductShouldReject() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineProduct wrongStage = new WbPipelineProduct();
        wrongStage.setId(88L);
        wrongStage.setFileResultId(10L);
        wrongStage.setStage(PipelineStage.CHUNK.name());
        when(pipelineProductDbService.getById(88L)).thenReturn(wrongStage);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.structure(10L, 88L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, e.getErrorCode());
        assertTrue(e.getMessage().contains("指定产物环节不匹配"), e.getMessage());
        verify(pipelineTaskDbService, never()).save(any());
    }

    /**
     * 组装：上游任务未成功按 40439 拒绝。
     *
     * <p>守护 {@code structure}：失败上游的产物被消费即失败。
     */
    @Test
    void structureWithFailedUpstreamTaskShouldThrow40439() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineProduct parseProduct = new WbPipelineProduct();
        parseProduct.setId(50L);
        parseProduct.setTaskId(UPSTREAM_TASK_ID);
        parseProduct.setFileResultId(10L);
        parseProduct.setStage(PipelineStage.PARSE.name());
        when(pipelineProductDbService.getById(50L)).thenReturn(parseProduct);
        // 产出任务已失败，产物行却已经落盘（孤儿产物）：下游门禁拒绝消费
        when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID)).thenReturn(upstreamTask(PipelineTaskStatus.FAILED));

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.structure(10L, 50L));

        assertEquals(ErrorCode.TASK_UPSTREAM_NOT_READY, e.getErrorCode());
        assertTrue(e.getMessage().contains(PipelineTaskStatus.FAILED.name()), e.getMessage());
        assertTrue(e.getMessage().contains("请重新触发上游环节"), e.getMessage());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    /**
     * 组装：上游部分成功在放行集内。
     *
     * <p>守护 {@code structure}：部分成功被拦即失败。
     */
    @Test
    void structureWithPartialSuccessUpstreamTaskShouldPass() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineProduct parseProduct = new WbPipelineProduct();
        parseProduct.setId(50L);
        parseProduct.setTaskId(UPSTREAM_TASK_ID);
        parseProduct.setFileResultId(10L);
        parseProduct.setStage(PipelineStage.PARSE.name());
        when(pipelineProductDbService.getById(50L)).thenReturn(parseProduct);
        when(pipelineTaskDbService.getById(UPSTREAM_TASK_ID))
                .thenReturn(upstreamTask(PipelineTaskStatus.PARTIAL_SUCCESS));
        existingStructureTask(50L, null);
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineTask.class).setId(52L);
            return true;
        });

        var response = service.structure(10L, 50L);

        // 部分成功在放行集内：任务照常新建并入队
        assertEquals(52L, response.getPipelineTaskId());
        verify(taskQueue).enqueue(52L);
    }

    /**
     * 组装：未指定上游解析产物按 40436 拒绝。
     *
     * <p>守护 {@code structure}：缺上游仍建任务即失败。
     */
    @Test
    void structureWithoutParseProductShouldThrow40436() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.structure(10L, null));
        assertEquals(ErrorCode.TASK_UPSTREAM_MISSING, e.getErrorCode());
        assertTrue(e.getMessage().contains("未指定上游解析产物"), e.getMessage());
        verify(pipelineTaskDbService, never()).save(any());
    }

    /**
     * 组装：执行键命中运行中任务按 40431 拒绝。
     *
     * <p>守护 {@code structure}：重复建任务即失败。
     */
    @Test
    void structureRunningShouldReject() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineProduct parseProduct = new WbPipelineProduct();
        parseProduct.setId(50L);
        parseProduct.setTaskId(UPSTREAM_TASK_ID);
        parseProduct.setFileResultId(10L);
        parseProduct.setStage(PipelineStage.PARSE.name());
        when(pipelineProductDbService.getById(50L)).thenReturn(parseProduct);
        WbPipelineTask running = new WbPipelineTask();
        running.setId(51L);
        running.setStatus(PipelineTaskStatus.RUNNING.name());
        existingStructureTask(50L, running);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.structure(10L, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        // 拒绝文案带任务号与状态：用户能看出是哪条任务占着执行键
        assertTrue(e.getMessage().contains("taskId=51"), e.getMessage());
        assertTrue(e.getMessage().contains(PipelineTaskStatus.RUNNING.name()), e.getMessage());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    /**
     * 组装：执行键命中排队任务按 40431 拒绝。
     *
     * <p>守护 {@code structure}：重复建任务即失败。
     */
    @Test
    void structureExistingQueuedTaskShouldReject40431() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineProduct parseProduct = new WbPipelineProduct();
        parseProduct.setId(50L);
        parseProduct.setTaskId(UPSTREAM_TASK_ID);
        parseProduct.setFileResultId(10L);
        parseProduct.setStage(PipelineStage.PARSE.name());
        when(pipelineProductDbService.getById(50L)).thenReturn(parseProduct);
        WbPipelineTask queued = new WbPipelineTask();
        queued.setId(51L);
        queued.setStatus(PipelineTaskStatus.QUEUED.name());
        existingStructureTask(50L, queued);

        // 执行键命中即拒绝，触发入口不再补投唤醒；QUEUED 补投归启动补偿与低频扫描
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.structure(10L, 50L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    /**
     * 详情：任务态、子步骤、摘要、大纲与告警冲突逐项下发。
     *
     * <p>守护 {@code structureDetail}：漏任一列即失败。
     */
    @Test
    void detailShouldAssembleTaskStepsSummaryAndOutline() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineTask task = new WbPipelineTask();
        task.setId(51L);
        task.setStage(PipelineStage.STRUCTURE.name());
        task.setStatus(PipelineTaskStatus.PARTIAL_SUCCESS.name());
        task.setProductId(50L);
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.STRUCTURE.name())).thenReturn(task);
        WbPipelineStepLog step = new WbPipelineStepLog();
        step.setStepName("结构组装");
        step.setStatus("SUCCESS");
        step.setWarningCount(1);
        step.setMatchedCount(5);
        step.setChangedCount(2);
        step.setAvgLen(400);
        when(stepLogDbService.listByTaskId(51L)).thenReturn(List.of(step));
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("def".repeat(16));
        product.setContentHash("def".repeat(16));
        // 产物行记下对象位置：详情读取按数据源与对象键定位
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        when(pipelineProductDbService.getById(50L)).thenReturn(product);
        String json = "{\"elements\":["
                + "{\"id\":\"h-1\",\"type\":\"TITLE\",\"text\":\"第一章\",\"level\":1},"
                + "{\"id\":\"n-1\",\"type\":\"PARAGRAPH\",\"text\":\"正文\",\"conflictStatus\":\"PRIMARY\"},"
                + "{\"id\":\"t-1\",\"type\":\"TABLE\",\"rows\":2,\"cols\":2,\"cells\":["
                + "{\"row\":0,\"col\":0,\"text\":\"A\",\"isHeader\":true},"
                + "{\"row\":0,\"col\":1,\"text\":\"B\",\"isHeader\":true}]}],"
                + "\"relations\":[{\"type\":\"PARENT_CHILD\",\"from\":\"h-1\",\"to\":\"n-1\"}],"
                + "\"quality\":{\"warnings\":[{\"code\":\"NOISE_PAGE\",\"level\":\"WARN\",\"message\":\"噪声页\"}],"
                + "\"conflicts\":[{\"primaryElementId\":\"n-1\",\"backupElementId\":\"n-2\",\"message\":\"无法裁决\"}]}}";
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "def".repeat(16))))
                .thenReturn(json.getBytes(StandardCharsets.UTF_8));

        StructureDetailVO detail = service.structureDetail(10L, null);

        assertEquals(51L, detail.getTaskId());
        assertEquals(PipelineTaskStatus.PARTIAL_SUCCESS.name(), detail.getStatus());
        assertEquals(1, detail.getSteps().size());
        assertEquals(1, detail.getSteps().getFirst().getWarningCount());
        assertEquals(5, detail.getSteps().getFirst().getMatchedCount());
        assertEquals(2, detail.getSteps().getFirst().getChangedCount());
        assertEquals(400, detail.getSteps().getFirst().getAvgLen());
        assertNotNull(detail.getSummary());
        assertEquals(3, detail.getSummary().getElementCount());
        assertEquals(1, detail.getSummary().getTitleCount());
        assertEquals(1, detail.getSummary().getRelationCount());
        assertEquals(1, detail.getSummary().getConflictCount());
        assertEquals(1, detail.getWarnings().size());
        assertEquals("n-1", detail.getConflicts().getFirst().getPrimaryElementId());
        assertEquals(3, detail.getOutline().size());
        assertEquals("TITLE", detail.getOutline().getFirst().getType());
        assertEquals("第一章", detail.getOutline().getFirst().getText());
        assertEquals("PRIMARY", detail.getOutline().get(1).getConflictStatus());
        assertEquals(2, detail.getOutline().get(2).getCells().size());
        assertTrue(detail.getOutline().get(2).getCells().getFirst().getIsHeader());
        // 阶段统计与摘要由产物现算：漏下发会让详情页缺「结构可用」结论
        assertNotNull(detail.getStageStats());
        assertEquals(3, detail.getStageStats().get("elementCount"));
        assertNotNull(detail.getStageSummary());
    }

    /**
     * 详情：无任务时各集合为空。
     *
     * <p>守护 {@code structureDetail}：无任务仍读产物即失败。
     */
    @Test
    void detailWithoutTaskShouldReturnEmptyCollections() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.STRUCTURE.name())).thenReturn(null);

        StructureDetailVO detail = service.structureDetail(10L, null);

        assertNotNull(detail.getWarnings());
        assertTrue(detail.getWarnings().isEmpty());
        assertNotNull(detail.getConflicts());
        assertNotNull(detail.getOutline());
    }

    /**
     * 详情：指定任务环节不符按 40001 拒绝。
     *
     * <p>守护 {@code structureDetail}：错环节任务被展示即失败。
     */
    @Test
    void detailWithWrongStageTaskShouldReject() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineTask parseTask = new WbPipelineTask();
        parseTask.setId(20L);
        parseTask.setFileResultId(10L);
        parseTask.setStage(PipelineStage.PARSE.name());
        when(pipelineTaskDbService.getById(20L)).thenReturn(parseTask);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.structureDetail(10L, 20L));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    /**
     * 详情：本人文件结果通过归属校验。
     *
     * <p>守护 {@code structureDetail}：本人也被拦即失败。
     */
    @Test
    void ownerFileResultShouldPassOwnershipCheck() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());

        // 归属校验放行后进入业务分支：没有解析产物时报"产物不存在"，而不是归属的 40401
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.structure(10L, 50L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 详情：产物对象位置不全按 40455 拒绝。
     *
     * <p>守护 {@code structureDetail}：半截位置去读对象即失败。
     */
    @Test
    void detailWithIncompleteProductLocationShouldReject40455() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineTask task = new WbPipelineTask();
        task.setId(51L);
        task.setStage(PipelineStage.STRUCTURE.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.STRUCTURE.name())).thenReturn(task);
        // 产物行缺 storageType / bucket：位置不完整，读取路径按「存储后端未配置」拒绝
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("def".repeat(16));
        when(pipelineProductDbService.getById(50L)).thenReturn(product);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.structureDetail(10L, null));

        assertEquals(ErrorCode.STORAGE_BACKEND_UNCONFIGURED, e.getErrorCode());
        verify(fileStorage, never()).getObject(any(ObjectRef.class));
    }

    /**
     * 详情：产物不可读时大纲留空、不阻断。
     *
     * <p>守护 {@code structureDetail}：读失败抛给用户即失败。
     */
    @Test
    void detailWithUnreadableProductShouldKeepOutlineEmpty() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineTask task = new WbPipelineTask();
        task.setId(51L);
        task.setStage(PipelineStage.STRUCTURE.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.STRUCTURE.name())).thenReturn(task);
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("def".repeat(16));
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        when(pipelineProductDbService.getById(50L)).thenReturn(product);
        // 非存储类读取失败（对象流异常）：记日志留空，不阻断详情
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "def".repeat(16))))
                .thenThrow(new IllegalStateException("对象流不可用"));

        StructureDetailVO detail = service.structureDetail(10L, null);

        assertEquals(51L, detail.getTaskId());
        assertNotNull(detail.getOutline());
        assertTrue(detail.getOutline().isEmpty());
        assertNotNull(detail.getWarnings());
        assertTrue(detail.getWarnings().isEmpty());
    }

    /** 让指定知识库归当前登录用户所有（归属校验要能过） */
    private void boundKnowledgeBase(Long id) {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(id);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        lenient().when(knowledgeBaseDbService.getActiveById(id)).thenReturn(kb);
    }


    /**
     * 指定上游产物属于别的文件结果：按 40404 拒绝。
     *
     * <p>守护 {@code requireParseProduct} 的归属校验：跨文件产物被消费会把 A 的结构写进 B。
     */
    @Test
    void structureWithForeignParseProductShouldReject() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineProduct foreign = new WbPipelineProduct();
        foreign.setId(88L);
        foreign.setFileResultId(99L);
        foreign.setStage(PipelineStage.PARSE.name());
        when(pipelineProductDbService.getById(88L)).thenReturn(foreign);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.structure(10L, 88L));

        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, e.getErrorCode());
        assertTrue(e.getMessage().contains("不属于该文件结果"), e.getMessage());
        verify(pipelineTaskDbService, never()).save(any());
    }
}
