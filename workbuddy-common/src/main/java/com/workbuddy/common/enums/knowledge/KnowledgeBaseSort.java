package com.workbuddy.common.enums.knowledge;

import com.workbuddy.common.utils.NullUtil;

/**
 * 知识库列表排序口径（kb_knowledge_base 分页列表）。
 *
 * <p>各口径独立生效，不叠加额外优先级。
 *
 * @author cxxl
 */
public enum KnowledgeBaseSort {

    /** 默认：按 id 倒序（新建的在前） */
    DEFAULT,

    /** 最近更新：按 update_time 倒序（同刻按 id 倒序） */
    UPDATED,

    /** 名称：按 name 升序（同名按 id 升序） */
    NAME;

    /**
     * 按名称解析排序口径；空值或未知值回落 {@link #DEFAULT}（不抛异常，避免旧客户端报错）。
     *
     * @param name 排序口径名（大小写不敏感）
     * @return 排序口径
     */
    public static KnowledgeBaseSort of(String name) {
        if (NullUtil.isNull(name) || name.isBlank()) {
            return DEFAULT;
        }
        for (KnowledgeBaseSort sort : values()) {
            if (sort.name().equalsIgnoreCase(name.trim())) {
                return sort;
            }
        }
        return DEFAULT;
    }
}
