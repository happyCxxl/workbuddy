package com.workbuddy.biz.task;

import java.util.function.BooleanSupplier;

/**
 * 轮询断言助手：按 {@code JAVA-TEST-005} 的处方等待异步簿记收敛（轮询 + 超时上限），替代固定时长休眠。
 */
public final class TestAwait {

    private TestAwait() {
    }

    /**
     * 轮询等待条件成立，超时抛 AssertionError。
     *
     * @param what      等待目标描述（超时报错时给出）
     * @param condition 轮询条件
     * @param timeoutMs 超时上限（毫秒）
     */
    public static void awaitUntil(String what, BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("等待超时: " + what + "（上限 " + timeoutMs + "ms）");
    }
}
