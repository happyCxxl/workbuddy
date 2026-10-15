package com.workbuddy.biz.task;

import com.workbuddy.biz.config.TaskExecutorConfig;
import com.workbuddy.biz.config.TaskQueueProperties;
import com.workbuddy.biz.service.db.KbPipelineTaskDbService;
import com.workbuddy.biz.task.runner.ChunkTaskRunner;
import com.workbuddy.biz.task.runner.EmbedTaskRunner;
import com.workbuddy.biz.task.runner.ParseTaskRunner;
import com.workbuddy.biz.task.runner.PreprocessTaskRunner;
import com.workbuddy.biz.task.runner.StructureTaskRunner;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.common.domain.entity.KbPipelineTask;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 解析消费循环单测（停机：在跑任务显式置 FAILED + 正常收尾任务不误标）。
 *
 * @author cxxl
 */
@ExtendWith(MockitoExtension.class)
class PipelineTaskConsumerTest {

    /** 多线程等待上限：宽裕值，避免慢机器抖动 */
    private static final long WAIT_SECONDS = 5;

    @Mock
    private TaskQueueSupport taskQueue;
    @Mock
    private KbPipelineTaskDbService pipelineTaskDbService;
    @Mock
    private ParseTaskRunner runner;
    @Mock
    private StructureTaskRunner structureRunner;
    @Mock
    private PreprocessTaskRunner preprocessRunner;
    @Mock
    private ChunkTaskRunner chunkRunner;
    @Mock
    private EmbedTaskRunner embedRunner;

    private TaskQueueProperties properties;

    private ThreadPoolTaskExecutor watchdogExecutor;
    private ThreadPoolTaskExecutor runnerExecutor;

    private PipelineTaskConsumer consumer;

    @BeforeEach
    void setUp() {
        properties = new TaskQueueProperties();
        properties.setConcurrency(2);
        properties.setClaimBatchSize(10);
        properties.setShutdownWait(Duration.ofMillis(200));
        buildExecutors();
        consumer = new PipelineTaskConsumer(taskQueue, pipelineTaskDbService, properties,
                runner, structureRunner, preprocessRunner, chunkRunner, embedRunner,
                watchdogExecutor, runnerExecutor);
    }

    @AfterEach
    void tearDown() {
        watchdogExecutor.destroy();
        runnerExecutor.destroy();
    }

    /** 复用生产同一个 bean 定义构造执行器（单测不另写一份池参数） */
    private void buildExecutors() {
        TaskExecutorConfig config = new TaskExecutorConfig();
        watchdogExecutor = config.taskWatchdogExecutor(properties);
        runnerExecutor = config.taskRunnerExecutor(properties);
    }

    /** 叫醒一次并返回一批任务；后续阻塞弹出为空（消费循环空转等待） */
    private void stubOneBatch(KbPipelineTask... tasks) {
        AtomicBoolean woken = new AtomicBoolean(false);
        when(taskQueue.blockingPop()).thenAnswer(inv -> woken.compareAndSet(false, true) ? "1" : null);
        when(pipelineTaskDbService.listQueuedByStages(any(), anyInt())).thenReturn(List.of(tasks));
    }

    private KbPipelineTask task(Long id, PipelineStage stage) {
        KbPipelineTask task = new KbPipelineTask();
        task.setId(id);
        task.setStage(stage.name());
        task.setStatus(PipelineTaskStatus.QUEUED.name());
        return task;
    }

    /** 模拟长任务：阻塞到放行；中途被中断不退出（真实 runner 不响应中断时即此形态） */
    private CountDownLatch runUntilReleased(Long taskId, CountDownLatch entered, CountDownLatch release) {
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            done.countDown();
            return null;
        }).when(runner).run(taskId);
        return done;
    }

    @Test
    void stopShouldMarkInFlightTaskFailedWithShutdownCode() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runUntilReleased(1L, entered, release);
        stubOneBatch(task(1L, PipelineStage.PARSE));

        consumer.start();
        assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "任务未进入执行");
        // 停机时任务仍在执行且超过 shutdownWait：判定未排空 → 中断并打停机码
        consumer.stop();
        release.countDown();

        verify(pipelineTaskDbService).finish(eq(1L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.TASK_SHUTDOWN_INTERRUPTED.name()), eq("服务停机中断，任务未完成"));
    }

    @Test
    void stopShouldNotMarkTaskThatFinishedBeforeStop() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = runUntilReleased(1L, entered, release);
        stubOneBatch(task(1L, PipelineStage.PARSE));

        consumer.start();
        assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "任务未进入执行");
        // 放行后 run 自然返回并移出在跑集合，停机时不该再打停机码
        release.countDown();
        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS), "任务未收尾");
        Thread.sleep(500);
        consumer.stop();

        verify(pipelineTaskDbService, never()).finish(any(), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.TASK_SHUTDOWN_INTERRUPTED.name()), anyString());
    }

    @Test
    void stopShouldLeaveUnclaimedQueuedTaskUntouched() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runUntilReleased(1L, entered, release);
        // 索引构建环节不在消费循环的五路分发内：扫到了也不领批（分发与产物归 BUILD_INDEX 自身链路）
        KbPipelineTask buildIndex = task(2L, PipelineStage.BUILD_INDEX);
        stubOneBatch(task(1L, PipelineStage.PARSE), buildIndex);

        consumer.start();
        assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "任务未进入执行");
        Thread.sleep(100);
        consumer.stop();
        release.countDown();

        // 停机只回写在跑任务：未被分发的 QUEUED 任务不受影响
        verify(runner, never()).run(2L);
        verify(pipelineTaskDbService, never()).finish(eq(2L), any(), anyString(), anyString());
        verify(pipelineTaskDbService).finish(eq(1L), eq(PipelineTaskStatus.FAILED),
                eq(PipelineTaskErrorCode.TASK_SHUTDOWN_INTERRUPTED.name()), eq("服务停机中断，任务未完成"));
    }
}
