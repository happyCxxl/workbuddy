package com.workbuddy.common.enums.parse;

/**
 * 信号类型（升级触发依据）：判定器输出 = 能力调用输入。
 */
public enum SignalType {

    /** 文本层缺失/异常（扫描页/乱码页/图片页）→ OCR */
    OCR_TEXT,

    /** 图片文字未识别（嵌入图片/结构缺口）→ OCR */
    OCR_IMAGE,

    /** 表格规则失败 → 表格模型 */
    TABLE,

    /** 版面规则失败 → 版面模型（预留：当前无事实产出方，版面能力接入后由 provider 产 ParseFact） */
    LAYOUT
}
