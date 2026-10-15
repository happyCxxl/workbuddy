package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workbuddy.common.domain.base.BaseInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 操作审计（表：kb_audit_log）。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("kb_audit_log")
public class KbAuditLog extends BaseInfo {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 操作类型（AuditActionType 枚举名） */
    private String actionType;

    /** 对象类型（如 KNOWLEDGE_BASE） */
    private String objectType;

    /** 对象 ID */
    private String objectId;

    /** 变更前摘要（JSON 文本） */
    private String beforeSummary;

    /** 变更后摘要（JSON 文本） */
    private String afterSummary;

    /** 操作用户 ID（无认证上下文为 null） */
    private Long userId;
}
