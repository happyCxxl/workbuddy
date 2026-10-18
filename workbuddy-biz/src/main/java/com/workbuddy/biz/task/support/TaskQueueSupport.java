package com.workbuddy.biz.task.support;

import com.workbuddy.biz.config.TaskQueueProperties;
import com.workbuddy.infra.redis.RedisQueueSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 任务唤醒队列：Redis 只当"叫号器"，DB 任务表是唯一状态账本；
 * 消费侧被叫醒后扫库领批、重读 DB 校验，消息内容（taskId）仅作参考。
 */
@Component
@RequiredArgsConstructor
public class TaskQueueSupport {

    private final RedisQueueSupport redisQueueSupport;
    private final TaskQueueProperties properties;

    /** 任务登记后入队（重复入队无害，消费侧重读 DB 校验）。 */
    public void enqueue(Long taskId) {
        redisQueueSupport.leftPush(properties.getQueueKey(), String.valueOf(taskId));
    }

    /** 阻塞弹出（消费循环接入前只定义不调用）。 */
    public String blockingPop() {
        return redisQueueSupport.rightPop(properties.getQueueKey(), properties.getWakeupTimeout());
    }
}
