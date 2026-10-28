package com.workbuddy.model.service.support.capacity;

import com.workbuddy.common.domain.model.chat.TokenUsage;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.ratelimit.BucketRateLimiter;
import com.workbuddy.model.ModelRef;
import com.workbuddy.model.service.support.route.CapacitySnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 模型容量守卫：RPM/TPM 双桶。
 *
 * <p>RPM 在调用前消费 1；TPM 不做估算预扣——调用前只看当前窗口是否还有余量，
 * 调用成功后按厂商回传的 {@code usage} 记账（无 usage 不扣）。这与参考网关的
 * 「按已实测水位准入」一致，避免为估算值单独维护一套 token 口径。
 */
@Component
@RequiredArgsConstructor
public class ModelCapacityGuard {

    /** 限额窗口：每分钟 */
    private static final int WINDOW_SECONDS = 60;

    private final BucketRateLimiter bucketRateLimiter;

    /**
     * 取容量快照（剩余比例）。
     *
     * @param ref      模型定位
     * @param rpmLimit 每分钟请求上限，可空
     * @param tpmLimit 每分钟 token 上限，可空
     * @return 容量快照；限额缺失时为空快照
     */
    public CapacitySnapshot snapshot(ModelRef ref, Integer rpmLimit, Integer tpmLimit) {
        if (invalid(rpmLimit) || invalid(tpmLimit)) {
            return CapacitySnapshot.EMPTY;
        }
        long rpmAvailable = bucketRateLimiter.availableTokens(rpmKey(ref), rpmLimit, WINDOW_SECONDS);
        long tpmAvailable = bucketRateLimiter.availableTokens(tpmKey(ref), tpmLimit, WINDOW_SECONDS);
        return new CapacitySnapshot(ratio(rpmAvailable, rpmLimit), ratio(tpmAvailable, tpmLimit));
    }

    /**
     * 尝试占用一次调用：先扣 RPM，再确认 TPM 窗口还有余量。
     *
     * @param ref      模型定位
     * @param rpmLimit 每分钟请求上限，可空
     * @param tpmLimit 每分钟 token 上限，可空
     * @return 可发起调用为 true；RPM 用尽或 TPM 已到水位为 false（已扣 RPM 会归还）
     */
    public boolean tryAcquire(ModelRef ref, Integer rpmLimit, Integer tpmLimit) {
        if (invalid(rpmLimit) || invalid(tpmLimit)) {
            return false;
        }
        if (!bucketRateLimiter.tryConsume(rpmKey(ref), 1, rpmLimit, WINDOW_SECONDS)) {
            return false;
        }
        if (bucketRateLimiter.availableTokens(tpmKey(ref), tpmLimit, WINDOW_SECONDS) <= 0) {
            bucketRateLimiter.addTokens(rpmKey(ref), 1, rpmLimit, WINDOW_SECONDS);
            return false;
        }
        return true;
    }

    /**
     * 调用成功后按真实用量扣 TPM；无 usage 或限额缺失时忽略。
     *
     * @param ref      模型定位
     * @param tpmLimit 每分钟 token 上限，可空
     * @param usage    厂商回传用量，可空
     */
    public void recordUsage(ModelRef ref, Integer tpmLimit, TokenUsage usage) {
        if (invalid(tpmLimit) || NullUtil.isNull(usage)) {
            return;
        }
        long tokens = usageTokens(usage);
        if (tokens <= 0) {
            return;
        }
        bucketRateLimiter.consumeIgnoringRateLimits(tpmKey(ref), tokens, tpmLimit, WINDOW_SECONDS);
    }

    private long usageTokens(TokenUsage usage) {
        if (NullUtil.isNotNull(usage.getTotalTokens())) {
            return usage.getTotalTokens();
        }
        long input = NullUtil.isNull(usage.getInputTokens()) ? 0 : usage.getInputTokens();
        long output = NullUtil.isNull(usage.getOutputTokens()) ? 0 : usage.getOutputTokens();
        return input + output;
    }

    private boolean invalid(Integer limit) {
        return NullUtil.isNull(limit) || limit <= 0;
    }

    private double ratio(long available, int capacity) {
        double value = (double) available / capacity;
        return Math.clamp(value, 0, 1);
    }

    private String rpmKey(ModelRef ref) {
        return "model:" + ref.vendorCode() + ":" + ref.modelKey() + ":rpm";
    }

    private String tpmKey(ModelRef ref) {
        return "model:" + ref.vendorCode() + ":" + ref.modelKey() + ":tpm";
    }
}
