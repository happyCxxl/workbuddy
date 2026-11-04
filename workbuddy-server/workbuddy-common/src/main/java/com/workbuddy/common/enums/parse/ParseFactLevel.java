package com.workbuddy.common.enums.parse;

/**
 * 解析事实级别：INFO 仅作为产物留痕，不参与信号判定；WARN 交给信号判定，作为降级/失败线索。
 */
public enum ParseFactLevel {

    /** 信息级事实：正常去重、剪枝、能力补强等，不产生质量告警 */
    INFO,

    /** 警告级事实：规则降级、能力失败、图片文字未识别等，参与信号判定 */
    WARN
}
