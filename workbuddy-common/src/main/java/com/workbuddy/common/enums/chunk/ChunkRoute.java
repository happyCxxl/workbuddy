package com.workbuddy.common.enums.chunk;

/**
 * 切片路由（策略配置分区）：routes 映射的四个分区键。
 * 与 {@link ChunkKind}（切片器分发口径）分工：路由含兜底分区，兜底不是切片 kind（它是正文路超长元素的降级模式）。
 */
public enum ChunkRoute {

    /** 正文路 */
    BODY("body"),

    /** 表格路 */
    TABLE("table"),

    /** 图片路 */
    IMAGE("image"),

    /** 兜底配置区（非切片 kind，见类注释） */
    FALLBACK("fallback");

    private final String key;

    ChunkRoute(String key) {
        this.key = key;
    }

    /** 策略快照中的序列化键（JSON 契约） */
    public String key() {
        return key;
    }
}
