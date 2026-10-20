package com.workbuddy.biz.task;

import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.support.access.ChainStorageSupport;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.biz.task.support.TaskTriggerSupport;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.storage.StorageRef;
import com.workbuddy.common.dto.response.task.StageTriggerVO;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 环节任务触发单测：存储一致性守卫（40453，委托 ChainStorageSupport）/ 执行键查重（命中一律 40431）/
 * 新建任务落库入队 / 入队失败落 FAILED 且异常继续抛出。
 */
@ExtendWith(MockitoExtension.class)
class TaskTriggerSupportTest {

    /** 链的档案所在数据源 */
    private static final Long ARCHIVE_SOURCE = 1L;

    /** 当前启用的数据源 */
    private static final Long CURRENT_SOURCE = 2L;

    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private WbFileResultDbService fileResultDbService;
    @Mock
    private WbSourceFileDbService sourceFileDbService;
    @Mock
    private FileStorage fileStorage;
    @Mock
    private StorageRouter storageRouter;
    @Mock
    private TaskQueueSupport taskQueue;

    private TaskTriggerSupport triggerSupport;

    @BeforeEach
    void setUp() {
        triggerSupport = new TaskTriggerSupport(pipelineTaskDbService,
                new ChainStorageSupport(fileResultDbService, sourceFileDbService, fileStorage, storageRouter),
                taskQueue);
    }

    /** 链的源文件档案：fileResult 10 → sourceFile 5 → fileId F-88 */
    private void stubFileChain() {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(10L);
        fileResult.setSourceFileId(5L);
        when(fileResultDbService.getById(10L)).thenReturn(fileResult);
        WbSourceFile sourceFile = new WbSourceFile();
        sourceFile.setId(5L);
        sourceFile.setFileId("F-88");
        when(sourceFileDbService.getById(5L)).thenReturn(sourceFile);
    }

    /** 组装环节执行键（文件结果 10 + 上游产物 50 + 无策略 0）上没有任务 */
    private void noExistingTask() {
        when(pipelineTaskDbService.getByExecKey(10L, PipelineStage.STRUCTURE.name(), 50L, 0L))
                .thenReturn(null);
    }

    private void savedTaskGetsId(long id) {
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbPipelineTask.class).setId(id);
            return true;
        });
    }

    /**
     * 守护 {@code newTaskShouldBeQueuedAndEnqueued}：该场景行为改动即失败。
     */
    @Test
    void newTaskShouldBeQueuedAndEnqueued() {
        noExistingTask();
        savedTaskGetsId(60L);

        StageTriggerVO vo = triggerSupport.trigger(10L, PipelineStage.STRUCTURE, 50L, null, null, "组装");

        assertEquals(60L, vo.getPipelineTaskId());
        ArgumentCaptor<WbPipelineTask> captor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(pipelineTaskDbService).save(captor.capture());
        assertEquals(PipelineTaskStatus.QUEUED.name(), captor.getValue().getStatus());
        assertEquals(50L, captor.getValue().getUpstreamProductId());
        assertEquals(0L, captor.getValue().getStrategyVersionId());
        verify(taskQueue).enqueue(60L);
        verify(pipelineTaskDbService, never()).failQueued(any(), anyString(), anyString());
    }

    /**
     * 守护 {@code enqueueFailureShouldFailTheTaskAndRethrow}：该场景行为改动即失败。
     */
    @Test
    void enqueueFailureShouldFailTheTaskAndRethrow() {
        noExistingTask();
        savedTaskGetsId(61L);
        doThrow(new IllegalStateException("redis down")).when(taskQueue).enqueue(61L);

        assertThrows(IllegalStateException.class,
                () -> triggerSupport.trigger(10L, PipelineStage.STRUCTURE, 50L, null, null, "组装"));

        // 库里不留"排不上队"的 QUEUED 任务：状态与事实一致，用户可直接重试
        verify(pipelineTaskDbService).failQueued(61L, PipelineTaskErrorCode.TASK_ENQUEUE_FAILED.name(),
                "任务入队失败，请重试");
    }

    /**
     * 守护 {@code existingRunningTaskShouldReject40431}：该场景行为改动即失败。
     */
    @Test
    void existingRunningTaskShouldReject40431() {
        WbPipelineTask running = new WbPipelineTask();
        running.setId(12L);
        running.setStatus(PipelineTaskStatus.RUNNING.name());
        when(pipelineTaskDbService.getByExecKey(10L, PipelineStage.STRUCTURE.name(), 50L, 0L))
                .thenReturn(running);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> triggerSupport.trigger(10L, PipelineStage.STRUCTURE, 50L, null, null, "组装"));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        // 拒绝文案带任务号与状态：用户能看出是哪条任务占着执行键
        assertTrue(e.getMessage().contains("taskId=12"), e.getMessage());
        assertTrue(e.getMessage().contains(PipelineTaskStatus.RUNNING.name()), e.getMessage());
        assertTrue(e.getMessage().contains("请勿重复触发"), e.getMessage());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    /**
     * 守护 {@code existingSucceededTaskShouldAlsoReject40431}：该场景行为改动即失败。
     */
    @Test
    void existingSucceededTaskShouldAlsoReject40431() {
        // 查重只看执行键、不看任务状态：成功任务同样占着这一档输入与策略
        WbPipelineTask succeeded = new WbPipelineTask();
        succeeded.setId(13L);
        succeeded.setStatus(PipelineTaskStatus.SUCCESS.name());
        when(pipelineTaskDbService.getByExecKey(10L, PipelineStage.STRUCTURE.name(), 50L, 0L))
                .thenReturn(succeeded);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> triggerSupport.trigger(10L, PipelineStage.STRUCTURE, 50L, null, null, "组装"));

        assertEquals(ErrorCode.TASK_ALREADY_PENDING, e.getErrorCode());
        assertTrue(e.getMessage().contains("taskId=13"), e.getMessage());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    /**
     * 守护 {@code parseTriggerShouldWriteZeroSentinelsIntoExecKey}：该场景行为改动即失败。
     */
    @Test
    void parseTriggerShouldWriteZeroSentinelsIntoExecKey() {
        // 解析是链路起点：上游产物与策略版本两档都按 0 落到执行键与任务行
        when(pipelineTaskDbService.getByExecKey(10L, PipelineStage.PARSE.name(), 0L, 0L)).thenReturn(null);
        savedTaskGetsId(64L);

        StageTriggerVO vo = triggerSupport.trigger(10L, PipelineStage.PARSE, null, null, null, "解析");

        assertEquals(64L, vo.getPipelineTaskId());
        ArgumentCaptor<WbPipelineTask> captor = ArgumentCaptor.forClass(WbPipelineTask.class);
        verify(pipelineTaskDbService).save(captor.capture());
        assertEquals(0L, captor.getValue().getUpstreamProductId());
        assertEquals(0L, captor.getValue().getStrategyVersionId());
        verify(taskQueue).enqueue(64L);
    }

    /**
     * 守护 {@code mismatchedStorageShouldReject40453BeforeAnyTaskWork}：该场景行为改动即失败。
     */
    @Test
    void mismatchedStorageShouldReject40453BeforeAnyTaskWork() {
        stubFileChain();
        when(fileStorage.refOf("F-88")).thenReturn(new StorageRef(StorageType.MINIO.getCode(), ARCHIVE_SOURCE));
        when(storageRouter.currentSourceId()).thenReturn(CURRENT_SOURCE);
        when(storageRouter.nameOf(ARCHIVE_SOURCE)).thenReturn("演示 MinIO");
        when(storageRouter.nameOf(CURRENT_SOURCE)).thenReturn("本地磁盘");

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> triggerSupport.trigger(10L, PipelineStage.STRUCTURE, 50L, null, null, "组装"));

        assertEquals(ErrorCode.STORAGE_TYPE_MISMATCH, e.getErrorCode());
        // 文案按数据源名称给出两侧，用户能直接看出是哪两个数据源
        assertTrue(e.getMessage().contains("演示 MinIO"), e.getMessage());
        assertTrue(e.getMessage().contains("本地磁盘"), e.getMessage());
        verify(pipelineTaskDbService, never()).save(any());
        verify(taskQueue, never()).enqueue(any());
    }

    /**
     * 守护 {@code matchedStorageShouldPassTheGuard}：该场景行为改动即失败。
     */
    @Test
    void matchedStorageShouldPassTheGuard() {
        stubFileChain();
        when(fileStorage.refOf("F-88")).thenReturn(new StorageRef(StorageType.MINIO.getCode(), CURRENT_SOURCE));
        when(storageRouter.currentSourceId()).thenReturn(CURRENT_SOURCE);
        noExistingTask();
        savedTaskGetsId(62L);

        StageTriggerVO vo = triggerSupport.trigger(10L, PipelineStage.STRUCTURE, 50L, null, null, "组装");

        assertEquals(62L, vo.getPipelineTaskId());
        verify(taskQueue).enqueue(62L);
    }

    /**
     * 守护 {@code missingFileChainShouldNotBlockTrigger}：该场景行为改动即失败。
     */
    @Test
    void missingFileChainShouldNotBlockTrigger() {
        // 链路缺环（无文件结果）时不拦：由读取路径按记录给出「存储后端未配置」
        when(fileResultDbService.getById(10L)).thenReturn(null);
        noExistingTask();
        savedTaskGetsId(63L);

        StageTriggerVO vo = triggerSupport.trigger(10L, PipelineStage.STRUCTURE, 50L, null, null, "组装");

        assertEquals(63L, vo.getPipelineTaskId());
    }
}
