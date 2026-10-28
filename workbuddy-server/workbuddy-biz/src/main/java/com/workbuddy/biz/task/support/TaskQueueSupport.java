package com.workbuddy.biz.task.support;

import com.workbuddy.biz.config.TaskQueueProperties;
import com.workbuddy.infra.redis.RedisQueueSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 任务唤醒队列：Redis 只当"叫号器"，DB 任务表是唯一状态账本；
 * 消费侧被叫醒后扫库领批、重读 DB 校验，消息内容（taskId）仅作参考。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskQueueSupport {

    private final RedisQueueSupport redisQueueSupport;
    private final TaskQueueProperties properties;

    /**
     * 任务入队：有实际事务时延迟到提交后，避免事务内访问 Redis；无事务时立即入队。
     *
     * <p>提交后推送失败只记日志，任务保持 QUEUED，由 {@code TaskStartupCompensator} 补投。
     *
     * @param taskId 任务 ID
     */
    public void enqueue(Long taskId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /** 事务提交后推送；失败只记日志。 */
                @Override
                public void afterCommit() {
                    pushQuietly(taskId);
                }
            });
            return;
        }
        leftPush(taskId);
    }

    /**
     * 阻塞取一个任务。
     *
     * @return 任务 ID；超时为 null
     */
    public String blockingPop() {
        return redisQueueSupport.rightPop(properties.getQueueKey(), properties.getWakeupTimeout());
    }

    /** 立即入队；失败向上抛，由调用方决定任务状态。 */
    private void leftPush(Long taskId) {
        redisQueueSupport.leftPush(properties.getQueueKey(), String.valueOf(taskId));
    }

    /** 提交后入队；此时事务已完成，失败只能记录并由启动补偿器兜底。 */
    private void pushQuietly(Long taskId) {
        try {
            leftPush(taskId);
        } catch (RuntimeException e) {
            log.error("===> TaskQueueSupport afterCommit 入队失败, taskId={}（任务保持 QUEUED，等待补偿器补投）",
                    taskId, e);
        }
    }
}
