package com.workbuddy.common.security.audit;

import com.workbuddy.common.enums.knowledge.AuditActionType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 审计事件：由业务模块发布，落库由持有 wb_audit_log 数据服务的模块监听处理。
 */
@Getter
@RequiredArgsConstructor
public class AuditEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 审计动作 */
    private final AuditActionType action;

    /** 对象类型（如 KB_USER / KNOWLEDGE_BASE） */
    private final String objectType;

    /** 对象 ID */
    private final Long objectId;

    /** 变更前摘要（JSON 文本，可空） */
    private final String beforeSummary;

    /** 变更后摘要（JSON 文本，可空） */
    private final String afterSummary;
}
