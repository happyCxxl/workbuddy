package com.workbuddy.biz.task.support;

import com.workbuddy.biz.config.TaskQueueProperties;
import com.workbuddy.infra.redis.RedisQueueSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 任务入队事务边界单测：有事务时延迟到提交后，回滚不入队，提交后失败不反噬已提交事务。
 */
@ExtendWith(MockitoExtension.class)
class TaskQueueSupportTest {

    @Mock
    private RedisQueueSupport redisQueueSupport;

    @Mock
    private TaskQueueProperties properties;

    private TaskQueueSupport support;

    /**
     * 每个用例重建入队助手。
     */
    @BeforeEach
    void setUp() {
        support = new TaskQueueSupport(redisQueueSupport, properties);
    }

    /**
     * 清理事务同步器，避免用例间残留。
     */
    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    /**
     * 无事务：立即入队，保持触发入口的原有失败语义。
     *
     * <p>守护 JAVA-TX-002：无事务时不应引入延迟。
     */
    @Test
    void enqueueWithoutTransactionShouldPushImmediately() {
        when(properties.getQueueKey()).thenReturn("task-queue");

        support.enqueue(7L);

        verify(redisQueueSupport).leftPush("task-queue", "7");
    }

    /**
     * 有事务：提交前不入队，afterCommit 后才入队。
     *
     * <p>守护 JAVA-TX-002：事务内不得访问 Redis。
     */
    @Test
    void enqueueInTransactionShouldWaitForCommit() {
        when(properties.getQueueKey()).thenReturn("task-queue");
        beginTransaction();

        support.enqueue(7L);

        verifyNoInteractions(redisQueueSupport);
        commitSynchronizations();
        verify(redisQueueSupport).leftPush("task-queue", "7");
    }

    /**
     * 事务回滚：不向 Redis 推送任何消息。
     *
     * <p>守护 JAVA-TX-002：回滚后不得遗留已入队的孤儿消息。
     */
    @Test
    void rollbackShouldNotPush() {
        beginTransaction();

        support.enqueue(7L);

        completeSynchronizations(TransactionSynchronization.STATUS_ROLLED_BACK);
        verifyNoInteractions(redisQueueSupport);
    }

    /**
     * 提交后入队失败：只记录日志，不把异常抛回已提交的事务；任务保持 QUEUED 等补偿器。
     *
     * <p>守护 JAVA-TX-002：afterCommit 失败不能反噬已提交的 DB 状态。
     */
    @Test
    void afterCommitPushFailureShouldNotThrow() {
        when(properties.getQueueKey()).thenReturn("task-queue");
        doThrow(new IllegalStateException("redis down"))
                .when(redisQueueSupport).leftPush("task-queue", "7");
        beginTransaction();
        support.enqueue(7L);

        assertDoesNotThrow(this::commitSynchronizations);
        verify(redisQueueSupport).leftPush("task-queue", "7");
    }

    /** 开启一个带同步器的实际事务上下文。 */
    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    /** 触发所有已注册同步器的 afterCommit。 */
    private void commitSynchronizations() {
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        synchronizations.forEach(TransactionSynchronization::afterCommit);
    }

    /** 触发所有已注册同步器的 afterCompletion。 */
    private void completeSynchronizations(int status) {
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        synchronizations.forEach(synchronization -> synchronization.afterCompletion(status));
    }
}
