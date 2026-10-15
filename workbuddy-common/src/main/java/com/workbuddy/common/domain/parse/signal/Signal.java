package com.workbuddy.common.domain.parse.signal;

import com.workbuddy.common.enums.parse.SignalSubtype;
import com.workbuddy.common.enums.parse.SignalType;
import com.workbuddy.common.utils.NullUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 信号：判定器输出，也是能力调用的输入（能力注册表按 type 查找实现）。
 * subtype 为结构化结论，是管线降级分支的控制流依据；evidence 仅作展示。
 *
 * @author cxxl
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Signal {

    /** 信号类型（SignalType 枚举名） */
    private String type;

    /** 区域（页码/元素引用描述） */
    private String region;

    /** 命中依据（仅展示） */
    private String evidence;

    /** 阈值快照（仅展示）：指标信号填指标快照文案（字符数/图片口径的实测值与配置值），事实信号填 "-" */
    private String threshold;

    /** 信号子类型（SignalSubtype 枚举名；控制流依据，可空） */
    private String subtype;

    public static Signal of(SignalType type, String region, String evidence, String threshold, SignalSubtype subtype) {
        return new Signal(type.name(), region, evidence, threshold, NullUtil.isNull(subtype) ? null : subtype.name());
    }
}
