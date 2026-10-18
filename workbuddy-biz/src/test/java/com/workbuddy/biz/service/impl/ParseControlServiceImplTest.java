package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.support.ChainStorageSupport;
import com.workbuddy.biz.service.support.FileResultAccessGuard;
import com.workbuddy.biz.service.support.TaskDetailSupport;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.biz.task.support.TaskTriggerSupport;
import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.parse.ParseDetailVO;
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
import java.time.LocalDateTime;
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
 * 解析控制面服务单测：触发防重/建任务入队、解析详情组装。
 *
 * @author cxxl
 */
@ExtendWith(MockitoExtension.class)
class ParseControlServiceImplTest {

    /** 产物所在的数据源 */
    private static final Long SOURCE_ID = 1L;

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

    private ParseControlServiceImpl service;

    @BeforeEach
    void setUp() {
        // 触发/详情助手为纯委托类，用真实实例（mock 会让返回失真）
        SecurityTestSupport.loginViewer();
        boundKnowledgeBase(10L);
        FileResultAccessGuard accessGuard = new FileResultAccessGuard(fileResultDbService, knowledgeBaseDbService);
        ChainStorageSupport chainStorage = new ChainStorageSupport(fileResultDbService, sourceFileDbService,
                fileStorage, storageRouter);
        service = new ParseControlServiceImpl(
                new TaskTriggerSupport(pipelineTaskDbService, chainStorage, taskQueue),
                new TaskDetailSupport(pipelineTaskDbService, stepLogDbService, pipelineProductDbService), fileStorage,
                accessGuard);
    }

    private WbFileResult fileResult() {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setKnowledgeBaseId(10L);
        return fileResult;
    }

    /** 解析执行键：文件结果 10 + 无上游（0）+ 无策略（0） */
    private void existingParseTask(WbPipelineTask task) {
        when(pipelineTaskDbService.getByExecKey(10L, PipelineStage.PARSE.name(), 0L, 0L)).thenReturn(task);
    }

    @Test
    void parseShouldCreateTaskAndEnqueue() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        existingParseTask(null);
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineTask.class).setId(21L);
            return true;
        });

        var response = service.parse(10L);

        assertEquals(21L, response.getPipelineTaskId());
        ArgumentCaptor<WbPipelineTask> captor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(pipelineTaskDbService).save(captor.capture());
        assertEquals(PipelineTaskStatus.QUEUED.name(), captor.getValue().getStatus());
        assertEquals(PipelineStage.PARSE.name(), captor.getValue().getStage());
        // 解析是链路起点：任务行的上游与策略两档都落 0 哨兵
        assertEquals(0L, captor.getValue().getUpstreamProductId());
        assertEquals(0L, captor.getValue().getStrategyVersionId());
        verify(taskQueue).enqueue(21L);
    }

    @Test
    void parseRunningShouldReject() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineTask running = new WbPipelineTask();
        running.setId(21L);
        running.setStatus(PipelineTaskStatus.RUNNING.name());
        existingParseTask(running);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.parse(10L));
        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void parseQueuedShouldReject40431() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineTask queued = new WbPipelineTask();
        queued.setId(21L);
        queued.setStatus(PipelineTaskStatus.QUEUED.name());
        existingParseTask(queued);

        // 执行键命中即拒绝，触发入口不再补投唤醒；QUEUED 补投归启动补偿与低频扫描
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.parse(10L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void parseSuccessShouldReject40431() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineTask success = new WbPipelineTask();
        success.setId(21L);
        success.setStatus(PipelineTaskStatus.SUCCESS.name());
        existingParseTask(success);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.parse(10L));
        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void parsePartialSuccessShouldReject40431() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineTask partial = new WbPipelineTask();
        partial.setId(21L);
        partial.setStatus(PipelineTaskStatus.PARTIAL_SUCCESS.name());
        existingParseTask(partial);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.parse(10L));
        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
    }

    @Test
    void parseFailedShouldReject40431() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineTask failed = new WbPipelineTask();
        failed.setId(21L);
        failed.setStatus(PipelineTaskStatus.FAILED.name());
        existingParseTask(failed);

        // 查重只看执行键：失败任务仍占着「无上游 + 无策略」这一档，触发入口不再按状态分流
        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.parse(10L));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    @Test
    void parseMissingFileResultShouldReject() {
        when(fileResultDbService.getById(10L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.parse(10L));
        assertEquals(ErrorCode.FILE_RESULT_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void detailShouldAssembleTaskStepsAndWarnings() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        WbPipelineTask task = new WbPipelineTask();
        task.setId(20L);
        task.setStage(PipelineStage.PARSE.name());
        task.setStatus(PipelineTaskStatus.PARTIAL_SUCCESS.name());
        task.setStartedAt(LocalDateTime.now());
        task.setProductId(50L);
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.PARSE.name())).thenReturn(task);
        WbPipelineStepLog step = new WbPipelineStepLog();
        step.setStepName("原生解析");
        step.setStatus("SUCCESS");
        step.setWarningCount(2);
        step.setMatchedCount(7);
        step.setChangedCount(3);
        step.setAvgLen(512);
        when(stepLogDbService.listByTaskId(20L)).thenReturn(List.of(step));
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("9f2c".repeat(16));
        product.setContentHash("9f2c".repeat(16));
        // 产物行记下对象位置：详情读取按数据源与对象键定位
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        when(pipelineProductDbService.getById(50L)).thenReturn(product);
        String json = "{\"quality\":{\"warnings\":[{\"code\":\"SCANNED_PAGE\",\"level\":\"WARN\","
                + "\"message\":\"第 page 2 无文本层（扫描页），OCR 暂未支持\"}]}}";
        when(fileStorage.getObject(ObjectRef.of(SOURCE_ID, StorageType.MINIO.getCode(), "artifacts", "9f2c".repeat(16))))
                .thenReturn(json.getBytes(StandardCharsets.UTF_8));

        ParseDetailVO detail = service.parseDetail(10L, null);

        assertEquals(20L, detail.getTaskId());
        assertEquals(PipelineTaskStatus.PARTIAL_SUCCESS.name(), detail.getStatus());
        assertEquals(1, detail.getSteps().size());
        assertEquals(2, detail.getSteps().getFirst().getWarningCount());
        assertEquals(7, detail.getSteps().getFirst().getMatchedCount());
        assertEquals(3, detail.getSteps().getFirst().getChangedCount());
        assertEquals(512, detail.getSteps().getFirst().getAvgLen());
        assertEquals(1, detail.getWarnings().size());
        assertTrue(detail.getWarnings().getFirst().contains("SCANNED_PAGE"));
    }

    @Test
    void detailWithIncompleteProductLocationShouldReject40455() {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(20L);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.PARSE.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.PARSE.name())).thenReturn(task);
        // 产物行缺 storageType / storageSourceId / bucket：位置不完整，读取路径按「存储后端未配置」拒绝
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId("9f2c".repeat(16));
        when(pipelineProductDbService.getById(50L)).thenReturn(product);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.parseDetail(10L, null));

        assertEquals(ErrorCode.STORAGE_BACKEND_UNCONFIGURED, e.getErrorCode());
        verify(fileStorage, never()).getObject(any(ObjectRef.class));
    }

    @Test
    void detailWithoutProductShouldReturnEmptyWarnings() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.PARSE.name())).thenReturn(null);

        ParseDetailVO detail = service.parseDetail(10L, null);

        assertNotNull(detail.getWarnings());
        assertTrue(detail.getWarnings().isEmpty());
    }

    @Test
    void otherUserFileResultShouldReject40401() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        // 归属看的是知识库归属：换一个登录用户，越权与"不存在"同样返回 40401
        SecurityTestSupport.loginOtherUser();

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.parse(10L));
        assertEquals(ErrorCode.KB_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void parseDetailOfOtherUserFileResultShouldReject40401() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        // 归属看的是知识库归属：换一个登录用户，越权与"不存在"同样返回 40401
        SecurityTestSupport.loginOtherUser();

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> service.parseDetail(10L, null));
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
