package com.workbuddy.common.domain.structure;

import lombok.Data;

/**
 * 续表判定结果（模型判断兜底输出）。
 */
@Data
public class ContinuationJudgeResult {

    /** 是否续表 */
    private Boolean isContinuation;

    /** 置信度（0~1） */
    private Double confidence;

    /** 模型名 */
    private String model;

    /** 模型版本 */
    private String version;
}
