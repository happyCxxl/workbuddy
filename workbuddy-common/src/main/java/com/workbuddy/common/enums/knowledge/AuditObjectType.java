package com.workbuddy.common.enums.knowledge;

import cn.hutool.core.util.StrUtil;

/**
 * 审计对象类型（wb_audit_log.object_type）：码值与中文名集中在这里，
 * 写入侧一律取 {@link #key()}，不写字符串字面量。
 *
 * @author cxxl
 */
public enum AuditObjectType {

    /** 知识库 */
    KNOWLEDGE_BASE("KNOWLEDGE_BASE", "知识库"),

    /** 索引版本 */
    INDEX_VERSION("INDEX_VERSION", "索引版本"),

    /** 账号（用户管理） */
    KB_USER("KB_USER", "账号"),

    /** 存储数据源 */
    STORAGE_SOURCE("STORAGE_SOURCE", "存储数据源"),

    /** 模型目录 */
    MODEL("MODEL", "模型"),

    /** 系统设置 */
    SYSTEM_SETTING("SYSTEM_SETTING", "系统设置");

    /** 码值（与库表 object_type 列一致） */
    private final String key;

    /** 中文名（展示用词表） */
    private final String label;

    AuditObjectType(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** 码值 */
    public String key() {
        return key;
    }

    /** 中文名 */
    public String label() {
        return label;
    }

    /** 按码值查找；未识别返回 null */
    public static AuditObjectType of(String key) {
        if (StrUtil.isBlank(key)) {
            return null;
        }
        for (AuditObjectType type : values()) {
            if (type.key.equalsIgnoreCase(key)) {
                return type;
            }
        }
        return null;
    }
}
