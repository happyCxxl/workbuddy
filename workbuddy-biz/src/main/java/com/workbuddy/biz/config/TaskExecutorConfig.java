package com.workbuddy.biz.config;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 任务消费线程池（具名 bean）：看门狗池与执行池分开，供消费循环按名注入。
 */
@Configuration
public class TaskExecutorConfig {

    /**
     * 看门狗池：每个在跑任务占一个线程，等执行池的结果。
     * 与执行池不可合并：合并后看门狗线程会占满线程，它等待的运行任务还排在同一个队列里，形成自死锁。
     */
    @Bean
    public ThreadPoolTaskExecutor taskWatchdogExecutor(TaskQueueProperties properties) {
        return build(properties, "task-watchdog-");
    }

    /** 执行池：实际跑各环节 Runner 的线程，与看门狗池一一对应 */
    @Bean
    public ThreadPoolTaskExecutor taskRunnerExecutor(TaskQueueProperties properties) {
        return build(properties, "task-runner-");
    }

    private ThreadPoolTaskExecutor build(TaskQueueProperties properties, String namePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getConcurrency());
        executor.setMaxPoolSize(properties.getConcurrency());
        executor.setQueueCapacity(properties.getClaimBatchSize());
        executor.setThreadFactory(ThreadFactoryBuilder.create().setNamePrefix(namePrefix).setDaemon(true).build());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 有界优雅关闭：先 shutdown() 收下已提交任务，等待上限到点再 shutdownNow() 中断
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) properties.getShutdownWait().toSeconds());
        executor.initialize();
        return executor;
    }
}
