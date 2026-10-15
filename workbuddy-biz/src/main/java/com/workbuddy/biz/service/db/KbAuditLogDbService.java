package com.workbuddy.biz.service.db;

import com.workbuddy.common.domain.entity.KbAuditLog;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.infra.persistence.InfraDbService;

/**
 * 操作审计数据访问服务（表：kb_audit_log）：append-only，只写入。
 *
 * @author cxxl
 */
public interface KbAuditLogDbService extends InfraDbService<KbAuditLog> {

    /**
     * 组装并落一条审计记录（与业务写入同事务，事务边界在业务 service）。
     *
     * @param action     审计动作
     * @param objectType 对象类型（如 KNOWLEDGE_BASE）
     * @param objectId   对象 ID
     * @param beforeJson 变更前摘要（JSON 文本，可空）
     * @param afterJson  变更后摘要（JSON 文本，可空）
     */
    void saveAudit(AuditActionType action, String objectType, Long objectId, String beforeJson, String afterJson);
}
