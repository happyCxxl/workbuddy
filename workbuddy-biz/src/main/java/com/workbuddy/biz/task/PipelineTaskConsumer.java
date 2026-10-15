package com.workbuddy.biz.task;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import com.workbuddy.biz.config.TaskQueueProperties;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.task.runner.ChunkTaskRunner;
import com.workbuddy.biz.task.runner.EmbedTaskRunner;
import com.workbuddy.biz.task.runner.ParseTaskRunner;
import com.workbuddy.biz.task.runner.PreprocessTaskRunner;
import com.workbuddy.biz.task.runner.StructureTaskRunner;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.PipelineTaskErrorCode;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.utils.NullUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.time.Duration;

/**
 * 解析消费循环：BRPOP 阻塞叫醒（空队零开销睡眠）→ 扫库领批 QUEUED 的 PARSE/STRUCTURE/PREPROCESS/CHUNK/EMBED 任务 →
 * 按 stage 分发到对应 Runner，线程池执行（看门狗按环节超时，超时回写 FAILED(EXECUTOR_TIMEOUT)）。
 * 多实例防重靠"条件更新领任务"（受影响行数=1 才执行，见各 Runner）。
 * 停机：停领批 → 关池等在跑任务至多 knowledge.task.shutdown-wait → 中断并回写 FAILED(TASK_SHUTDOWN_INTERRUPTED)。
 * 其他环节任务的领批与分发随各自环节 Runner 落地后接入。
 *
 * @author cxxl
 */
@Slf4j
@Component
public class PipelineTaskConsumer {

    private final TaskQueueSupport taskQueue;
    private final WbPipelineTaskDbService pipelineTaskDbService;
    private final TaskQueueProperties properties;
    private final ParseTaskRunner runner;
    private final StructureTaskRunner structureRunner;
    private final PreprocessTaskRunner preprocessRunner;
    private final ChunkTaskRunner chunkRunner;
    private final EmbedTaskRunner embedRunner;
    private final ThreadPoolTaskExecutor watchdogExecutor;
    private final ThreadPoolTaskExecutor runnerExecutor;

    private volatile boolean running = false;

    /** 执行中的 taskId 集合（停机时据此显式置失败） */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    /** 两个执行器 bean 同类型，构造参数按 bean 名限定（不用 Lombok 生成构造器，它不带走 @Qualifier） */
    public PipelineTaskConsumer(TaskQueueSupport taskQueue, WbPipelineTaskDbService pipelineTaskDbService,
                             TaskQueueProperties properties, ParseTaskRunner runner,
                             StructureTaskRunner structureRunner, PreprocessTaskRunner preprocessRunner,
                             ChunkTaskRunner chunkRunner, EmbedTaskRunner embedRunner,
                             @Qualifier("taskWatchdogExecutor") ThreadPoolTaskExecutor watchdogExecutor,
                             @Qualifier("taskRunnerExecutor") ThreadPoolTaskExecutor runnerExecutor) {
        this.taskQueue = taskQueue;
        this.pipelineTaskDbService = pipelineTaskDbService;
        this.properties = properties;
        this.runner = runner;
        this.structureRunner = structureRunner;
        this.preprocessRunner = preprocessRunner;
        this.chunkRunner = chunkRunner;
        this.embedRunner = embedRunner;
        this.watchdogExecutor = watchdogExecutor;
        this.runnerExecutor = runnerExecutor;
    }

    /** 应用就绪后启动（单次；daemon 线程随进程退出） */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Thread dispatcher = ThreadFactoryBuilder.create().setNamePrefix("parse-dispatcher-").setDaemon(true).build()
                .newThread(this::loop);
        dispatcher.start();
        log.info("解析消费循环启动, concurrency={}, claimBatchSize={}, timeout={}",
                properties.getConcurrency(), properties.getClaimBatchSize(),
                properties.timeoutOf(PipelineStage.PARSE.name()));
    }

    private void loop() {
        while (running) {
            try {
                // 阻塞叫醒；超时返回 null = 未被叫醒 → 继续等待，不扫库（无消息零开销）
                String wakeup = taskQueue.blockingPop();
                if (NullUtil.isNull(wakeup)) {
                    continue;
                }
                // 消息内容仅参考（DB 是唯一账本）：被叫醒才扫库领批
                List<WbPipelineTask> batch = pipelineTaskDbService.listQueuedByStages(
                        List.of(PipelineStage.PARSE, PipelineStage.STRUCTURE,
                                PipelineStage.PREPROCESS, PipelineStage.CHUNK,
                                PipelineStage.EMBED),
                        properties.getClaimBatchSize());
                for (WbPipelineTask task : batch) {
                    if (!running) {
                        break;
                    }
                    dispatch(task);
                }
            } catch (Exception e) {
                log.warn("解析消费循环异常, 稍后重试", e);
                sleepQuietly();
            }
        }
    }

    /** 按 stage 选 Runner 动作，提交看门狗池；未知环节跳过并告警 */
    private void dispatch(WbPipelineTask task) {
        Runnable action;
        if (PipelineStage.PARSE.name().equals(task.getStage())) {
            action = () -> runner.run(task.getId());
        } else if (PipelineStage.STRUCTURE.name().equals(task.getStage())) {
            action = () -> structureRunner.run(task.getId());
        } else if (PipelineStage.PREPROCESS.name().equals(task.getStage())) {
            action = () -> preprocessRunner.run(task.getId());
        } else if (PipelineStage.CHUNK.name().equals(task.getStage())) {
            action = () -> chunkRunner.run(task.getId());
        } else if (PipelineStage.EMBED.name().equals(task.getStage())) {
            action = () -> embedRunner.run(task.getId());
        } else {
            // 未知环节：跳过并告警（后续环节加 Runner 即接入，主循环零改动）
            log.warn("未知环节任务跳过, taskId={}, stage={}", task.getId(), task.getStage());
            return;
        }
        try {
            watchdogExecutor.execute(() -> {
                inFlight.add(task.getId());
                try {
                    runWithTimeout(task.getId(), properties.timeoutOf(task.getStage()), action);
                } finally {
                    inFlight.remove(task.getId());
                }
            });
        } catch (RejectedExecutionException e) {
            // 线程池满：任务保持 QUEUED，等下次叫醒（下次提交/补偿都会再叫醒）
        }
    }

    /** 优雅停机：停领批 → 关池等上限 → 到点中断 → 仍未结束的任务显式置 FAILED(TASK_SHUTDOWN_INTERRUPTED) */
    @PreDestroy
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        // ThreadPoolTaskExecutor.shutdown()：shutdown() → 等 awaitTerminationSeconds → 到点 shutdownNow()
        // 两池顺序等，最坏合计等 2 × knowledge.task.shutdown-wait
        watchdogExecutor.shutdown();
        runnerExecutor.shutdown();
        int marked = markInFlightFailed();
        log.info("解析消费循环停止, 仍在执行并显式置失败的任务数={}", marked);
    }

    /** 遍历执行中 taskId 逐条条件回写失败；runner 已写终态时受影响行数为 0，不覆盖 */
    private int markInFlightFailed() {
        int marked = 0;
        for (Long taskId : inFlight) {
            pipelineTaskDbService.finish(taskId, PipelineTaskStatus.FAILED,
                    PipelineTaskErrorCode.TASK_SHUTDOWN_INTERRUPTED.name(), "服务停机中断，任务未完成");
            marked++;
        }
        return marked;
    }

    /** 看门狗：Future 限时等待；超时协作中断并回写 FAILED(EXECUTOR_TIMEOUT) */
    private void runWithTimeout(Long taskId, Duration timeout, Runnable action) {
        Future<?> future = null;
        try {
            future = runnerExecutor.submit(action);
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            // 终态回写带 RUNNING 条件：与 runner 的兜底回写互斥，后到者受影响行数为 0
            pipelineTaskDbService.finish(taskId, PipelineTaskStatus.FAILED,
                    PipelineTaskErrorCode.EXECUTOR_TIMEOUT.name(), "单任务超时（看门狗判定）");
            log.warn("任务超时, taskId={}", taskId);
        } catch (RejectedExecutionException e) {
            // 执行池满：任务保持 QUEUED
        } catch (Exception e) {
            log.warn("任务执行异常, taskId={}", taskId, e);
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
