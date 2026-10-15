package com.workbuddy.common.enums.preprocess;

/**
 * 视图元素处置状态（字典）：只落在派生视图副本，原结构树不变。
 * 落库/落产物形式：name()。
 *
 * @author cxxl
 */
public enum ViewElementStatus {

    /** 正常（无标记/规则未命中） */
    NORMAL,

    /** 页眉：仅标记（默认，仍参与内容流） */
    MARKED_HEADER,

    /** 页脚：仅标记（默认，仍参与内容流） */
    MARKED_FOOTER,

    /** 页眉：剔除（不进 normalizedText 内容流，展示视图完整） */
    EXCLUDED_HEADER,

    /** 页脚：剔除（不进 normalizedText 内容流，展示视图完整） */
    EXCLUDED_FOOTER,

    /** 目录：仅标记 */
    MARKED_TOC,

    /** 目录：剔除 */
    EXCLUDED_TOC,

    /** 重复内容：剔除档（normalizedText 只保留一份，本元素为重复份） */
    REPEATED,

    /** 重复内容：标记档（仍进 normalizedText 内容流） */
    MARKED_REPEAT,

    /** 噪声：仅标记 */
    NOISE,

    /** 噪声：剔除 */
    EXCLUDED_NOISE,

    /** 图片：仅保留引用（OCR 预留，预处理环节不改动） */
    IMAGE_REF_ONLY,

    /** 组装环节冲突被裁决方（BACKUP）：不进 normalizedText 内容流 */
    BACKUP_SKIPPED
}
