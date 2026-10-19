package com.workbuddy.biz.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * 任务消费线程池 bean 单测（具名注册 + 同名限定注入，挡住两个同类型 bean 的歧义）。
 */
class TaskExecutorConfigTest {

    /** 按 bean 名限定注入的探针：同名限定取不到时容器上下文启动失败 */
    @Configuration
    static class ProbeConfig {

        @Bean
        Map<String, ThreadPoolTaskExecutor> executorProbe(
                @Qualifier("taskWatchdogExecutor") ThreadPoolTaskExecutor watchdogExecutor,
                @Qualifier("taskRunnerExecutor") ThreadPoolTaskExecutor runnerExecutor) {
            return Map.of("watchdog", watchdogExecutor, "runner", runnerExecutor);
        }
    }

    /**
     * 守护 {@code contextShouldRegisterTwoNamedTaskExecutors}：该场景行为改动即失败。
     */
    @Test
    void contextShouldRegisterTwoNamedTaskExecutors() {
        TaskQueueProperties properties = new TaskQueueProperties();
        properties.setConcurrency(3);
        properties.setClaimBatchSize(7);
        properties.setShutdownWait(Duration.ofSeconds(5));
        new ApplicationContextRunner()
                .withUserConfiguration(TaskExecutorConfig.class, ProbeConfig.class)
                .withBean(TaskQueueProperties.class, () -> properties)
                .run(context -> {
                    ThreadPoolTaskExecutor watchdog =
                            assertInstanceOf(ThreadPoolTaskExecutor.class, context.getBean("taskWatchdogExecutor"));
                    ThreadPoolTaskExecutor runner =
                            assertInstanceOf(ThreadPoolTaskExecutor.class, context.getBean("taskRunnerExecutor"));
                    try {
                        ThreadPoolExecutor watchdogPool = watchdog.getThreadPoolExecutor();
                        ThreadPoolExecutor runnerPool = runner.getThreadPoolExecutor();
                        assertEquals(3, watchdogPool.getCorePoolSize());
                        assertEquals(3, watchdogPool.getMaximumPoolSize());
                        assertEquals(3, runnerPool.getCorePoolSize());
                        assertEquals(3, runnerPool.getMaximumPoolSize());
                        // 有界队列容量取自 claimBatchSize（Spring 用 LinkedBlockingQueue）；拒绝策略为显式 AbortPolicy
                        assertEquals(7, watchdogPool.getQueue().remainingCapacity());
                        assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, watchdogPool.getRejectedExecutionHandler());
                        assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, runnerPool.getRejectedExecutionHandler());
                        Map<?, ?> probe = context.getBean("executorProbe", Map.class);
                        assertEquals(2, probe.size());
                    } finally {
                        watchdog.shutdown();
                        runner.shutdown();
                    }
                });
    }
}
