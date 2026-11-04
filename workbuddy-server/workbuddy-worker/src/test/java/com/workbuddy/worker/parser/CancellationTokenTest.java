package com.workbuddy.worker.parser;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析取消令牌单测：未取消不抛，取消后检查点抛 CancellationException。
 */
class CancellationTokenTest {

    /**
     * 守护 {@code cancelShouldMakeNextCheckThrow}：取消后到检查点必须退出。
     */
    @Test
    void cancelShouldMakeNextCheckThrow() {
        CancellationToken token = new CancellationToken();

        assertDoesNotThrow(token::throwIfCancelled);
        token.cancel();

        assertTrue(token.isCancelled());
        assertThrows(CancellationException.class, token::throwIfCancelled);
    }
}
