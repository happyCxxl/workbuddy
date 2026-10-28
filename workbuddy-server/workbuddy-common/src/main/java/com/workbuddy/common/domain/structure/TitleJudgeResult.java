package com.workbuddy.common.domain.structure;

import lombok.Data;

/**
 * 标题判定结果（模型判断兜底输出）。
 */
@Data
public class TitleJudgeResult {

    /** 是否标题 */
    private Boolean isTitle;

    /** 标题层级（1 起；isTitle=false 时为空） */
    private Integer level;

    /** 置信度（0~1） */
    private Double confidence;

    /** 模型名 */
    private String model;

    /** 模型版本 */
    private String version;
}
