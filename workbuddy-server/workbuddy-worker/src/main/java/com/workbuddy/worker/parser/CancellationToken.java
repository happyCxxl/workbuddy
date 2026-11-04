package com.workbuddy.worker.parser;

import lombok.Getter;

import java.util.concurrent.CancellationException;

/**
 * 解析取消令牌：看门狗超时或服务停机时置位，解析器在页/单元边界检查并提前退出。
 * 与线程中断标志分离，避免第三方库清掉中断后无法感知取消。
 */
@Getter
public final class CancellationToken {

    /**
     * -- GETTER --
     *  是否已取消。
     */
    private volatile boolean cancelled;

    /**
     * 置为已取消。
     */
    public void cancel() {
        cancelled = true;
    }

    /**
     * 已取消时抛出 {@link CancellationException}，供解析循环在边界处调用。
     */
    public void throwIfCancelled() {
        if (cancelled) {
            throw new CancellationException("解析已取消（超时/停机）");
        }
    }
}
