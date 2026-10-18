package com.workbuddy.common.enums.preprocess;

/**
 * 预处理三态处置动作。
 */
public enum PreprocessAction {

    /** 保留原样 */
    KEEP,

    /** 保留但标注（进检索内容流，带标记供下游/前端区分） */
    MARK,

    /** 剔除出检索内容流（视图保留） */
    EXCLUDE
}
