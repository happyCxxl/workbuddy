package com.workbuddy.common.domain.model.calllog;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 模型调用概览：同一时间范围与同一组维度过滤下，明细表上的聚合结果。
 *
 * <p>口径（D16 / D19）：不做日聚合表，概览直接查明细 —— 调用总数、成功与失败数、各专项状态数、
 * 未知用量条数与未知占比、已知 token 合计、已知成本合计。
 * token 与成本合计取明细表的 {@code SUM}，未知行（null）天然被跳过，不参与合计；
 * 范围内没有调用时聚合列为 null，未知占比也为 null（不按 0 记，避免"无数据"与"全部已知"混用）。
 *
 * @author cxxl
 */
@Data
public class ModelCallLogSummary implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 调用总数 */
    private Long calls;

    /** 成功数（status = SUCCESS） */
    private Long successCalls;

    /** 失败数（status 不是 SUCCESS 的全部） */
    private Long failedCalls;

    /** 超时数 */
    private Long timeoutCalls;

    /** 被限流数 */
    private Long rateLimitedCalls;

    /** 用量未知的条数（usage_unknown = 1） */
    private Long unknownCalls;

    /** 未知占比（unknownCalls ÷ calls，四位小数，保留 HALF_UP；无调用为 null） */
    private BigDecimal unknownRatio;

    /** 已知总 token 合计（全为未知时为 null） */
    private Long totalTokens;

    /** 已知成本合计（全为未知或单价缺失时为 null） */
    private BigDecimal costTotal;
}
