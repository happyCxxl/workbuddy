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
                "{\"role\":\"USER\"}", "{\"role\":\"ADMIN\"}");

        auditEventListener.onAuditEvent(event);

        verify(kbAuditLogDbService).saveAudit(AuditActionType.USER_UPDATE, "KB_USER", 1001L,
                "{\"role\":\"USER\"}", "{\"role\":\"ADMIN\"}");

        org.mockito.ArgumentCaptor<AuditActionType> action = org.mockito.ArgumentCaptor.forClass(AuditActionType.class);
        org.mockito.ArgumentCaptor<Long> objectId = org.mockito.ArgumentCaptor.forClass(Long.class);
        org.mockito.ArgumentCaptor<String> before = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> after = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kbAuditLogDbService).saveAudit(action.capture(), anyString(), objectId.capture(),
                before.capture(), after.capture());
        assertEquals(AuditActionType.USER_UPDATE, action.getValue());
        assertEquals(1001L, objectId.getValue());
        assertEquals("{\"role\":\"USER\"}", before.getValue());
        assertEquals("{\"role\":\"ADMIN\"}", after.getValue());
    }

    /**
     * 守护 {@code shouldForwardNullSummariesAsIs}：该场景行为改动即失败。
     */
    @Test
    void shouldForwardNullSummariesAsIs() {
        // 删除场景 after 为空、创建场景 before 为空，都要求原样透传
        AuditEvent event = new AuditEvent(AuditActionType.USER_CREATE, "KB_USER", 1002L, null, "{}");

        auditEventListener.onAuditEvent(event);

        verify(kbAuditLogDbService).saveAudit(AuditActionType.USER_CREATE, "KB_USER", 1002L, null, "{}");

        org.mockito.ArgumentCaptor<String> before = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> after = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kbAuditLogDbService).saveAudit(eq(AuditActionType.USER_CREATE), eq("KB_USER"),
                eq(1002L), before.capture(), after.capture());
        org.junit.jupiter.api.Assertions.assertNull(before.getValue());
        assertEquals("{}", after.getValue());
    }
}
