package com.workbuddy.common.domain.parse;

import com.workbuddy.common.enums.parse.QualityWarningCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 质量告警：只标记不阻断，是否继续由成功单元占比门槛统一裁决。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QualityWarning {

    /** 告警码（QualityWarningCode 枚举名） */
    private String code;

    /** 关联元素 ID（可空） */
    private String elementId;

    /** 级别：WARN / INFO */
    private String level;

    /** 说明 */
    private String message;

    public static QualityWarning of(QualityWarningCode code, String elementId, String level, String message) {
        return new QualityWarning(code.name(), elementId, level, message);
    }
}
