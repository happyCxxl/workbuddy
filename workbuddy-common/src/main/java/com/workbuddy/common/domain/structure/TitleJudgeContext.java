package com.workbuddy.common.domain.structure;

import lombok.Data;

import java.util.List;

/**
 * 标题判定上下文（模型判断兜底输入）。
 */
@Data
public class TitleJudgeContext {

    /** 候选文本 */
    private String candidateText;

    /** 字号（pt，可空） */
    private Double fontSize;

    /** 是否加粗 */
    private Boolean bold;

    /** 命中的编号模式（可空） */
    private String numberingPattern;

    /** 前后文元素文本（限长） */
    private List<String> contextTexts;
}
