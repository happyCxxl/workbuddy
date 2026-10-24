package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workbuddy.common.domain.base.BaseInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 操作审计（表：wb_audit_log）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wb_audit_log")
public class WbAuditLog extends BaseInfo {

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

    /** 操作载荷（JSON 文本）：注解驱动时为方法入参，显式发布时可含 before/after */
    private String payload;

    /** 操作用户 ID（无认证上下文为 null） */
    private Long userId;
}
