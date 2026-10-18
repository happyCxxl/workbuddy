package com.workbuddy.biz.listener;

import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.security.audit.AuditEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

/**
 * 审计事件监听测试：事件字段应原样落到审计数据服务。
 */
@ExtendWith(MockitoExtension.class)
class AuditEventListenerTest {

    @Mock
    private WbAuditLogDbService kbAuditLogDbService;

    @InjectMocks
    private AuditEventListener auditEventListener;

    @Test
    void shouldForwardEventFieldsToAuditService() {
        AuditEvent event = new AuditEvent(AuditActionType.USER_UPDATE, "KB_USER", 1001L,
                "{\"role\":\"USER\"}", "{\"role\":\"ADMIN\"}");

        auditEventListener.onAuditEvent(event);

        verify(kbAuditLogDbService).saveAudit(AuditActionType.USER_UPDATE, "KB_USER", 1001L,
                "{\"role\":\"USER\"}", "{\"role\":\"ADMIN\"}");
    }

    @Test
    void shouldForwardNullSummariesAsIs() {
        // 删除场景 after 为空、创建场景 before 为空，都要求原样透传
        AuditEvent event = new AuditEvent(AuditActionType.USER_CREATE, "KB_USER", 1002L, null, "{}");

        auditEventListener.onAuditEvent(event);

        verify(kbAuditLogDbService).saveAudit(AuditActionType.USER_CREATE, "KB_USER", 1002L, null, "{}");
    }
}
