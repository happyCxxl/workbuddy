package com.workbuddy.biz.listener;

import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.security.audit.AuditEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    /**
     * 守护 {@code shouldForwardEventFieldsToAuditService}：该场景行为改动即失败。
     */
    @Test
    void shouldForwardEventFieldsToAuditService() {
        AuditEvent event = new AuditEvent(AuditActionType.USER_UPDATE, "KB_USER", 1001L,
                "{\"after\":{\"role\":\"ADMIN\"}}");

        auditEventListener.onAuditEvent(event);

        verify(kbAuditLogDbService).saveAudit(AuditActionType.USER_UPDATE, "KB_USER", 1001L,
                "{\"after\":{\"role\":\"ADMIN\"}}");

        org.mockito.ArgumentCaptor<AuditActionType> action = org.mockito.ArgumentCaptor.forClass(AuditActionType.class);
        org.mockito.ArgumentCaptor<Long> objectId = org.mockito.ArgumentCaptor.forClass(Long.class);
        org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kbAuditLogDbService).saveAudit(action.capture(), anyString(), objectId.capture(), payload.capture());
        assertEquals(AuditActionType.USER_UPDATE, action.getValue());
        assertEquals(1001L, objectId.getValue());
        assertEquals("{\"after\":{\"role\":\"ADMIN\"}}", payload.getValue());
    }

    /**
     * 守护 {@code shouldForwardNullPayloadAsIs}：无载荷的事件必须原样透传 null。
     */
    @Test
    void shouldForwardNullPayloadAsIs() {
        AuditEvent event = new AuditEvent(AuditActionType.USER_PASSWORD_CHANGE, "KB_USER", 1002L, null);

        auditEventListener.onAuditEvent(event);

        verify(kbAuditLogDbService).saveAudit(AuditActionType.USER_PASSWORD_CHANGE, "KB_USER", 1002L, null);

        org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kbAuditLogDbService).saveAudit(eq(AuditActionType.USER_PASSWORD_CHANGE), eq("KB_USER"),
                eq(1002L), payload.capture());
        assertNull(payload.getValue());
    }
}
