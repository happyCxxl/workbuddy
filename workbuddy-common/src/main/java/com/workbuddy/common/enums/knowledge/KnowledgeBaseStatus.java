package com.workbuddy.common.enums.knowledge;

import com.workbuddy.common.utils.NullUtil;
import lombok.Getter;

/**
 * 知识库状态（wb_knowledge_base.status，TINYINT 码值）。
 * 1 启用（ACTIVE）/ 0 停用（DISABLED）；逻辑删除由 del_flag 承载，与本枚举无关。
 *
 * @author cxxl
 */
@Getter
public enum KnowledgeBaseStatus {

    /** 启用 */
    ACTIVE(1),

    /** 停用（拒绝新文件接入与检索，7.8） */
    DISABLED(0);

    private final int code;

    KnowledgeBaseStatus(int code) {
        this.code = code;
    }

    /**
     * 按码值转枚举；未知码值抛异常（防御脏数据）。
     */
    public static KnowledgeBaseStatus of(Integer code) {
        if (NullUtil.isNull(code)) {
            return null;
        }
        for (KnowledgeBaseStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知知识库状态码: " + code);
    }
}
