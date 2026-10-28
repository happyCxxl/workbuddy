package com.workbuddy.common.enums.structure;

/**
 * 冲突裁决状态（字典）：同框不同内容且无法裁决时，两路元素的留存标记。
 * 落库/落产物形式：name()。
 */
public enum ConflictStatus {

    /** 主路（裁决保留方，冲突中的首选） */
    PRIMARY,

    /** 被裁决方（降为备选，随主路保留） */
    BACKUP
}
