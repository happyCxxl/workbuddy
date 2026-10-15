package com.workbuddy.biz.service.db.impl;

import com.workbuddy.biz.mapper.WbAuditLogMapper;
import com.workbuddy.common.domain.entity.WbAuditLog;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.security.KnowledgeUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DbService 单测：saveAudit 的审计行组装规则（登录态操作人/字段映射/无认证兜底）。
 *
 * @author cxxl
 */
class WbAuditLogDbServiceImplTest {

    private WbAuditLogMapper mapper;

    private WbAuditLogDbServiceImpl impl;

    @BeforeEach
    void setUp() {
        mapper = mock(WbAuditLogMapper.class);
        impl = new WbAuditLogDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void saveAuditShouldAssembleAndInsert() {
        doAnswer(inv -> {
            WbAuditLog a = inv.getArgument(0);
            a.setId(9L);
            return 1;
        }).when(mapper).insert(any(WbAuditLog.class));

        impl.saveAudit(AuditActionType.DISABLE, "KNOWLEDGE_BASE", 4L, "{\"status\":1}", "{\"status\":0}");

        ArgumentCaptor<WbAuditLog> cap = ArgumentCaptor.forClass(WbAuditLog.class);
        verify(mapper).insert(cap.capture());
        WbAuditLog audit = cap.getValue();
        assertEquals("DISABLE", audit.getActionType());
        assertEquals("KNOWLEDGE_BASE", audit.getObjectType());
        assertEquals("4", audit.getObjectId());
        assertEquals("{\"status\":1}", audit.getBeforeSummary());
        assertEquals("{\"status\":0}", audit.getAfterSummary());
    }

    @Test
    void saveAuditShouldTakeOperatorFromLoginContext() {
        authenticateAsOperator();
        doAnswer(inv -> {
            WbAuditLog a = inv.getArgument(0);
            a.setId(10L);
            return 1;
        }).when(mapper).insert(any(WbAuditLog.class));

        impl.saveAudit(AuditActionType.CREATE, "KNOWLEDGE_BASE", 5L, null, "{\"name\":\"招标知识库\"}");

        ArgumentCaptor<WbAuditLog> cap = ArgumentCaptor.forClass(WbAuditLog.class);
        verify(mapper).insert(cap.capture());
        WbAuditLog audit = cap.getValue();
        assertEquals("zhangsan", audit.getCreateBy());
        assertEquals(9L, audit.getUserId());
    }

    @Test
    void saveAuditShouldFallbackWithoutLoginContext() {
        doAnswer(inv -> {
            WbAuditLog a = inv.getArgument(0);
            a.setId(11L);
            return 1;
        }).when(mapper).insert(any(WbAuditLog.class));

        impl.saveAudit(AuditActionType.DELETE, "KNOWLEDGE_BASE", 6L, "{\"name\":\"旧库\"}", null);

        ArgumentCaptor<WbAuditLog> cap = ArgumentCaptor.forClass(WbAuditLog.class);
        verify(mapper).insert(cap.capture());
        WbAuditLog audit = cap.getValue();
        assertEquals("system", audit.getCreateBy());
        assertNull(audit.getUserId());
    }

    /** 把固定操作人写进 Spring Security 上下文 */
    private static void authenticateAsOperator() {
        KnowledgeUser user = mock(KnowledgeUser.class);
        when(user.getId()).thenReturn(9L);
        when(user.getUsername()).thenReturn("zhangsan");
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(user);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }
}
