package com.workbuddy.worker.embedding.check;

import java.util.List;

/**
 * 四关校验结果：passed=true 通过；否则 problems 非空且 retryable 指明失败类别
 * （true=数量/空值坏值，批次整体重试；false=维度/度量冲突，拒绝写入）。
 *
 * @param passed    是否通过
 * @param retryable 失败是否可重试
 * @param problems  问题描述
 */
public record ConsistencyResult(boolean passed, boolean retryable, List<String> problems) {

    /**
     * 通过。
     *
     * @return 通过结果
     */
    public static ConsistencyResult pass() {
        return new ConsistencyResult(true, true, List.of());
    }

    /**
     * 失败。
     *
     * @param retryable 是否可重试
     * @param problems  问题描述
     * @return 失败结果
     */
    public static ConsistencyResult fail(boolean retryable, List<String> problems) {
        return new ConsistencyResult(false, retryable, problems);
    }
}
