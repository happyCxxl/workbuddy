package com.workbuddy.model.service.support.capacity;

import com.workbuddy.common.domain.model.chat.TokenUsage;
import com.workbuddy.infra.ratelimit.BucketRateLimiter;
import com.workbuddy.model.ModelRef;
import com.workbuddy.model.service.support.route.CapacitySnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 容量守卫单测：双桶快照、RPM 扣减、TPM 水位检查与按真实 usage 记账。
 */
@ExtendWith(MockitoExtension.class)
class ModelCapacityGuardTest {

    private static final ModelRef REF = new ModelRef("dashscope", "qwen-max");

    private static final String RPM_KEY = "model:dashscope:qwen-max:rpm";

    private static final String TPM_KEY = "model:dashscope:qwen-max:tpm";

    @Mock
    private BucketRateLimiter bucketRateLimiter;

    private ModelCapacityGuard guard;

    private ModelCapacityGuard guard() {
        return new ModelCapacityGuard(bucketRateLimiter);
    }

    /** 快照：剩余量折算比例（{@code shouldSnapshotRatios}）。 */
    @Test
    void shouldSnapshotRatios() {
        guard = guard();
        when(bucketRateLimiter.availableTokens(RPM_KEY, 60, 60)).thenReturn(30L);
        when(bucketRateLimiter.availableTokens(TPM_KEY, 1000, 60)).thenReturn(250L);

        CapacitySnapshot snapshot = guard.snapshot(REF, 60, 1000);

        assertEquals(0.5, snapshot.rpmRatio(), 0.0001);
        assertEquals(0.25, snapshot.tpmRatio(), 0.0001);
        assertEquals(0.25, snapshot.score(), 0.0001);
    }

    /** 限额缺失：空快照且不访问桶（{@code shouldReturnEmptyWithoutLimits}）。 */
    @Test
    void shouldReturnEmptyWithoutLimits() {
        guard = guard();

        assertEquals(CapacitySnapshot.EMPTY, guard.snapshot(REF, null, 1000));
        assertFalse(guard.tryAcquire(REF, 60, null));
        verifyNoInteractions(bucketRateLimiter);
    }

    /** RPM 有余量且 TPM 未到水位：允许调用（{@code shouldAcquireWhenTpmHasRemaining}）。 */
    @Test
    void shouldAcquireWhenTpmHasRemaining() {
        guard = guard();
        when(bucketRateLimiter.tryConsume(RPM_KEY, 1, 60, 60)).thenReturn(true);
        when(bucketRateLimiter.availableTokens(TPM_KEY, 1000, 60)).thenReturn(500L);

        assertTrue(guard.tryAcquire(REF, 60, 1000));
    }

    /** RPM 不足：直接 false（{@code shouldRejectWhenRpmExhausted}）。 */
    @Test
    void shouldRejectWhenRpmExhausted() {
        guard = guard();
        when(bucketRateLimiter.tryConsume(RPM_KEY, 1, 60, 60)).thenReturn(false);

        assertFalse(guard.tryAcquire(REF, 60, 1000));
    }

    /** TPM 已到水位：归还 RPM 后 false（{@code shouldRefundRpmWhenTpmExhausted}）。 */
    @Test
    void shouldRefundRpmWhenTpmExhausted() {
        guard = guard();
        when(bucketRateLimiter.tryConsume(RPM_KEY, 1, 60, 60)).thenReturn(true);
        when(bucketRateLimiter.availableTokens(TPM_KEY, 1000, 60)).thenReturn(0L);

        assertFalse(guard.tryAcquire(REF, 60, 1000));
        verify(bucketRateLimiter).addTokens(RPM_KEY, 1, 60, 60);
    }

    /** 调用后按 usage 记账（{@code shouldRecordUsage}）。 */
    @Test
    void shouldRecordUsage() {
        guard = guard();
        TokenUsage usage = new TokenUsage();
        usage.setInputTokens(60);
        usage.setOutputTokens(40);
        usage.setTotalTokens(100);

        guard.recordUsage(REF, 1000, usage);

        verify(bucketRateLimiter).consumeIgnoringRateLimits(TPM_KEY, 100, 1000, 60);
    }

    /** 无 usage 或限额缺失：不记账（{@code shouldIgnoreMissingUsage}）。 */
    @Test
    void shouldIgnoreMissingUsage() {
        guard = guard();

        guard.recordUsage(REF, 1000, null);
        guard.recordUsage(REF, null, new TokenUsage());

        verifyNoInteractions(bucketRateLimiter);
    }
}
