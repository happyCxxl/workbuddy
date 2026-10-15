package com.workbuddy.common.domain.parse.signal;

import com.workbuddy.common.enums.parse.SignalType;
import lombok.Data;

/**
 * 解析期事实（解析器产出的原始发现，尚未按阈值判定）：
 * 结构缺口（OOXML drawing/OLE 仅图片）、表格规则失败区域、版面异常区域等。
 * SignalDetector 把事实 + 页级指标按阈值转成 Signal。
 *
 * @author cxxl
 */
@Data
public class ParseFact {

    /** 事实类型：该事实转成的信号类型（SignalType 枚举，由解析器回填；为空时判定器忽略该事实） */
    private SignalType type;

    /** 区域描述（页/元素引用） */
    private String region;

    /** 命中依据（如"第 3 页表格规则失败"） */
    private String evidence;
}
