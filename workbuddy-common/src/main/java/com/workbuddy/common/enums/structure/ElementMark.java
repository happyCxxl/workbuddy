package com.workbuddy.common.enums.structure;

/**
 * 元素级标记（字典）：识别环节（解析/组装）写入、处置环节（预处理）读取。
 * 只增不删；标记不影响结构树与阅读顺序。
 *
 * @author cxxl
 */
public enum ElementMark {

    /** 目录行（解析环节行级特征识别，组装环节透传；整页聚合判定在预处理环节） */
    TOC_LINE,

    /** 重复段落（组装环节识别；本元素为除首份外的重复份） */
    REPEATED_SEGMENT
}
