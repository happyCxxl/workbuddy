package com.workbuddy.worker.embedding.check;

import java.util.List;

/**
 * 四关校验结果：passed=true 通过；否则 problems 非空且 retryable 指明失败类别
 * （true=数量/空值坏值，批次整体重试；false=维度/度量冲突，拒绝写入）。
 */
public record ConsistencyResult(boolean passed, boolean retryable, List<String> problems) {

    /** 通过。 */
    public static ConsistencyResult pass() {
        return new ConsistencyResult(true, true, List.of());
    }

    /** 失败（retryable=true 可重试 / false 拒绝）。 */
    public static ConsistencyResult fail(boolean retryable, List<String> problems) {
        return new ConsistencyResult(false, retryable, problems);
    }
}
