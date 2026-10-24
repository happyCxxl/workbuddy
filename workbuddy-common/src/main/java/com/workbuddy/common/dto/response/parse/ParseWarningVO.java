package com.workbuddy.common.dto.response.parse;

import lombok.Data;

/**
 * 结构化质量告警（解析详情展示用）：在原有文案之外给出可筛选/可跳页的字段。
 */
@Data
public class ParseWarningVO {

    /** 告警码（QualityWarningCode 枚举名） */
    private String code;

    /** 级别：WARN / INFO */
    private String level;

    /** 告警说明 */
    private String message;

    /** 从说明中解析出的文档页；解析不到时为空 */
    private Integer page;

    /** 关联元素 ID（可空） */
    private String elementId;
}
