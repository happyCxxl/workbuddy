package com.workbuddy.common.domain.structure;

import lombok.Data;

/**
 * 标题推定证据：层级推定可审计（级联层/字号/加粗/编号模式/模型证据）。
 */
@Data
public class TitleEvidence {

    /** 级联层：style / font-signal / number-pattern / model */
    private String cascade;

    /** 字号（pt） */
    private Double fontSize;

    /** 是否加粗 */
    private Boolean bold;

    /** 命中的编号模式（如 第X章 / 1.1 / （一）） */
    private String pattern;

    /** 模型判定证据（模型兜底启用时；含模型名/版本/置信度摘要） */
    private String modelEvidence;
}
