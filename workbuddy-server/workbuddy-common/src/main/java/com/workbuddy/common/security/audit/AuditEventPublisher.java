package com.workbuddy.common.security.audit;

import com.workbuddy.common.enums.knowledge.AuditActionType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 审计事件发布器：业务侧统一通过它落审计，不直接依赖审计表数据访问层。
 *
 * <p>事件同步派发：审计写入与业务操作处于同一事务（与既有 saveAudit 口径一致）。
 */
@Component
@RequiredArgsConstructor
public class AuditEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 发布审计事件。
     *
     * @param action     审计动作
     * @param objectType 对象类型
     * @param objectId   对象 ID
     * @param payload    操作载荷（JSON 文本，可空）
     */
    public void publish(AuditActionType action, String objectType, Long objectId, String payload) {
        eventPublisher.publishEvent(new AuditEvent(action, objectType, objectId, payload));
    }
}
