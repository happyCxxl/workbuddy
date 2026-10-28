package com.workbuddy.biz.listener;

import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.common.security.audit.AuditEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 审计事件监听：把跨模块发布的 {@link AuditEvent} 落到 wb_audit_log。
 *
 * <p>监听为同步调用，审计写入与发布方的业务操作处于同一事务：
 * 业务回滚时审计一并回滚，不会留下"操作没生效却有审计"的脏记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventListener {

    private final WbAuditLogDbService kbAuditLogDbService;

    /**
     * 审计事件落库：动作、对象与操作载荷原样写入审计表。
     *
     * @param event 审计事件
     */
    @EventListener
    public void onAuditEvent(AuditEvent event) {
        kbAuditLogDbService.saveAudit(event.getAction(), event.getObjectType(), event.getObjectId(),
                event.getPayload());
        log.info("===> AuditEventListener 审计落库, action={}, objectType={}, objectId={}",
                event.getAction(), event.getObjectType(), event.getObjectId());
    }
}
