package com.workbuddy.common.enums.chunk;

/**
 * 内容类型路由目标：正文 / 表格 / 图片。
 * 兜底不是路由目标——它是正文路超长元素的降级切片模式（contentType=FALLBACK + fallbackReason）。
 *
 * @author cxxl
 */
public enum ChunkKind {

    /** 正文（段落/列表/页眉页脚仅标记态等文本元素） */
    BODY,

    /** 表格 */
    TABLE,

    /** 图片（图注占位片） */
    IMAGE
}
