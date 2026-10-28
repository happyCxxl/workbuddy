package com.workbuddy.biz.task;

import com.workbuddy.biz.config.ConfigFixtures;
import com.workbuddy.biz.config.TaskQueueProperties;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.infra.redis.RedisLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 任务队列补偿单测（补投 QUEUED + RUNNING 孤儿恢复）。
 */
@ExtendWith(MockitoExtension.class)
class TaskStartupCompensatorTest {

    private static final String LOCK_KEY = "knowledge:task:queue:compensate-lock";

    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private TaskQueueSupport taskQueue;
    @Mock
    private RedisLock redisLock;

    private TaskQueueProperties properties;

    private TaskStartupCompensator compensator;

    @BeforeEach
    void setUp() {
        properties = ConfigFixtures.task();
        compensator = new TaskStartupCompensator(pipelineTaskDbService, taskQueue, redisLock, properties);
    }

    private void stubLockAcquired() {
        when(redisLock.tryLock(eq(LOCK_KEY), eq(Duration.ofSeconds(30)))).thenReturn("token");
    }

    /**
     * 守护 {@code onStartupShouldReEnqueueStaleQueuedTasks}：该场景行为改动即失败。
     */
    @Test
    void onStartupShouldReEnqueueStaleQueuedTasks() {
        stubLockAcquired();
        WbPipelineTask task1 = new WbPipelineTask();
        task1.setId(1L);
        WbPipelineTask task2 = new WbPipelineTask();
        task2.setId(2L);
        when(pipelineTaskDbService.listStaleQueued(any(LocalDateTime.class))).thenReturn(List.of(task1, task2));
        when(pipelineTaskDbService.listStaleRunning(any(LocalDateTime.class))).thenReturn(List.of());

        compensator.onStartup();

        verify(taskQueue).enqueue(1L);
        verify(taskQueue).enqueue(2L);
        // 补投与孤儿恢复各抢锁释放一次
        verify(redisLock, times(2)).tryLock(eq(LOCK_KEY), eq(Duration.ofSeconds(30)));
        verify(redisLock, times(2)).unlock(eq(LOCK_KEY), eq("token"));
        assertTrue(mockingDetails(redisLock).getInvocations().stream()
                .anyMatch(inv -> "unlock".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code onStartupShouldRecoverStaleRunningOrphans}：该场景行为改动即失败。
     */
    @Test
    void onStartupShouldRecoverStaleRunningOrphans() {
        stubLockAcquired();
        when(pipelineTaskDbService.listStaleQueued(any(LocalDateTime.class))).thenReturn(List.of());
        WbPipelineTask orphan = new WbPipelineTask();
        orphan.setId(7L);
        orphan.setStage(PipelineStage.PARSE.name());
        orphan.setStartedAt(LocalDateTime.now().minusMinutes(30));
        when(pipelineTaskDbService.listStaleRunning(any(LocalDateTime.class))).thenReturn(List.of(orphan));
        when(pipelineTaskDbService.finish(eq(7L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.EXECUTOR_TIMEOUT.name()), anyString())).thenReturn(1);

        compensator.onStartup();

        verify(pipelineTaskDbService).finish(eq(7L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.EXECUTOR_TIMEOUT.name()), anyString());
        assertTrue(mockingDetails(pipelineTaskDbService).getInvocations().stream()
                .anyMatch(inv -> "finish".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code orphanWithinStageTimeoutShouldNotBeRecovered}：该场景行为改动即失败。
     */
    @Test
    void orphanWithinStageTimeoutShouldNotBeRecovered() {
        stubLockAcquired();
        when(pipelineTaskDbService.listStaleQueued(any(LocalDateTime.class))).thenReturn(List.of());
        WbPipelineTask fresh = new WbPipelineTask();
        fresh.setId(8L);
        fresh.setStage(PipelineStage.PARSE.name());
        fresh.setStartedAt(LocalDateTime.now().minusSeconds(30));
        when(pipelineTaskDbService.listStaleRunning(any(LocalDateTime.class))).thenReturn(List.of(fresh));

        compensator.onStartup();

        verify(pipelineTaskDbService, never()).finish(any(), any(), any(), any());
        assertTrue(mockingDetails(pipelineTaskDbService).getInvocations().stream()
                .noneMatch(inv -> "finish".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code onStartupShouldSkipWhenLockNotAcquired}：该场景行为改动即失败。
     */
    @Test
    void onStartupShouldSkipWhenLockNotAcquired() {
        when(redisLock.tryLock(eq(LOCK_KEY), eq(Duration.ofSeconds(30)))).thenReturn(null);

        compensator.onStartup();

        verify(pipelineTaskDbService, never()).listStaleQueued(any(LocalDateTime.class));
        verify(pipelineTaskDbService, never()).listStaleRunning(any(LocalDateTime.class));
        verify(taskQueue, never()).enqueue(any());
        verify(redisLock, never()).unlock(anyString(), anyString());
        assertTrue(mockingDetails(redisLock).getInvocations().stream()
                .noneMatch(inv -> "unlock".equals(inv.getMethod().getName())));
    }

    /**
     * 守护 {@code scheduledSweepShouldNoopWhenDisabled}：该场景行为改动即失败。
     */
    @Test
    void scheduledSweepShouldNoopWhenDisabled() {
        properties.setLowFreqSweepEnabled(false);

        compensator.scheduledSweep();

        verifyNoInteractions(redisLock, pipelineTaskDbService, taskQueue);
    }

    /**
     * 守护 {@code scheduledSweepShouldCompensateWhenEnabled}：该场景行为改动即失败。
     */
    @Test
    void scheduledSweepShouldCompensateWhenEnabled() {
        properties.setLowFreqSweepEnabled(true);
        stubLockAcquired();
        when(pipelineTaskDbService.listStaleQueued(any(LocalDateTime.class))).thenReturn(List.of());
        when(pipelineTaskDbService.listStaleRunning(any(LocalDateTime.class))).thenReturn(List.of());

        compensator.scheduledSweep();

        verify(pipelineTaskDbService).listStaleQueued(any(LocalDateTime.class));
        verify(redisLock, times(2)).tryLock(eq(LOCK_KEY), eq(Duration.ofSeconds(30)));
        assertTrue(mockingDetails(redisLock).getInvocations().stream()
                .anyMatch(inv -> "tryLock".equals(inv.getMethod().getName())));
    }
}
