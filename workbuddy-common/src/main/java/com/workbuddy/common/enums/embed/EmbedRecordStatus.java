package com.workbuddy.common.enums.embed;

import cn.hutool.core.util.StrUtil;

/**
 * 向量记录状态：SUCCESS（新算）/ CACHED（账本复用命中）/ SKIPPED（父片/空文本跳过）/ FAILED（批次失败）。
 */
public enum EmbedRecordStatus {

    /** 网关新算成功 */
    SUCCESS("网关新算成功"),

    /** 账本复用命中（免网关调用） */
    CACHED("账本复用命中"),

    /** 跳过（父片/空文本，跳过即口径） */
    SKIPPED("跳过"),

    /** 失败（批次重试耗尽） */
    FAILED("失败");

    /** 说明（人类可读） */
    private final String desc;

    EmbedRecordStatus(String desc) {
        this.desc = desc;
    }

    /** 说明（人类可读）。 */
    public String desc() {
        return desc;
    }

    /** 按状态名精确查找；未识别返回 null。 */
    public static EmbedRecordStatus of(String name) {
        if (StrUtil.isBlank(name)) {
            return null;
        }
        for (EmbedRecordStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        return null;
    }
}
