package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.KnowledgeBaseDbService;
import com.workbuddy.biz.service.db.KbFileResultDbService;
import com.workbuddy.biz.service.db.KbPipelineProductDbService;
import com.workbuddy.biz.service.db.KbPipelineStepLogDbService;
import com.workbuddy.biz.service.db.KbPipelineTaskDbService;
import com.workbuddy.biz.service.db.KbSourceFileDbService;
import com.workbuddy.biz.service.support.ChainStorageSupport;
import com.workbuddy.biz.service.support.FileResultAccessGuard;
import com.workbuddy.biz.service.support.TaskDetailSupport;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.biz.task.support.TaskTriggerSupport;
import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.entity.KnowledgeBase;
import com.workbuddy.common.domain.entity.KbFileResult;
import com.workbuddy.common.domain.entity.KbPipelineProduct;
import com.workbuddy.common.domain.entity.KbPipelineStepLog;
import com.workbuddy.common.domain.entity.KbPipelineTask;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.parse.ParseDetailVO;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.service.FileStorage;
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
    private KbFileResultDbService fileResultDbService;
    @Mock
    private KbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private KbPipelineProductDbService pipelineProductDbService;
    @Mock
    private KbPipelineStepLogDbService stepLogDbService;
    @Mock
    private TaskQueueSupport taskQueue;
    @Mock
    private KnowledgeBaseDbService knowledgeBaseDbService;
    @Mock
    private KbSourceFileDbService sourceFileDbService;
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

    private KbFileResult fileResult() {
        KbFileResult fileResult = new KbFileResult();
        fileResult.setId(10L);
        fileResult.setKnowledgeBaseId(10L);
        return fileResult;
    }

    /** 解析执行键：文件结果 10 + 无上游（0）+ 无策略（0） */
    private void existingParseTask(KbPipelineTask task) {
        when(pipelineTaskDbService.getByExecKey(10L, PipelineStage.PARSE.name(), 0L, 0L)).thenReturn(task);
    }

    @Test
    void parseShouldCreateTaskAndEnqueue() {
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        existingParseTask(null);
        when(pipelineTaskDbService.save(any(KbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, KbPipelineTask.class).setId(21L);
            return true;
        });

        var response = service.parse(10L);

        assertEquals(21L, response.getPipelineTaskId());
        ArgumentCaptor<KbPipelineTask> captor = ArgumentCaptor.forClass(KbPipelineTask.class);
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
        KbPipelineTask running = new KbPipelineTask();
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
        KbPipelineTask queued = new KbPipelineTask();
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
        KbPipelineTask success = new KbPipelineTask();
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
        KbPipelineTask partial = new KbPipelineTask();
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
        KbPipelineTask failed = new KbPipelineTask();
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
        KbPipelineTask task = new KbPipelineTask();
        task.setId(20L);
        task.setStage(PipelineStage.PARSE.name());
        task.setStatus(PipelineTaskStatus.PARTIAL_SUCCESS.name());
        task.setStartedAt(LocalDateTime.now());
        task.setProductId(50L);
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.PARSE.name())).thenReturn(task);
        KbPipelineStepLog step = new KbPipelineStepLog();
        step.setStepName("原生解析");
        step.setStatus("SUCCESS");
        step.setWarningCount(2);
        step.setMatchedCount(7);
        step.setChangedCount(3);
        step.setAvgLen(512);
        when(stepLogDbService.listByTaskId(20L)).thenReturn(List.of(step));
        KbPipelineProduct product = new KbPipelineProduct();
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
        KbPipelineTask task = new KbPipelineTask();
        task.setId(20L);
        task.setFileResultId(10L);
        task.setStage(PipelineStage.PARSE.name());
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(50L);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult());
        when(pipelineTaskDbService.getByFileResultIdAndStage(10L, PipelineStage.PARSE.name())).thenReturn(task);
        // 产物行缺 storageType / storageSourceId / bucket：位置不完整，读取路径按「存储后端未配置」拒绝
        KbPipelineProduct product = new KbPipelineProduct();
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
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(id);
        kb.setUserId(SecurityTestSupport.VIEWER_ID);
        lenient().when(knowledgeBaseDbService.getActiveById(id)).thenReturn(kb);
    }

}
