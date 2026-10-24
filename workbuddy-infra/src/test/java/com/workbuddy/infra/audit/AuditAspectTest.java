package com.workbuddy.infra.audit;

import com.workbuddy.common.annotation.Audit;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.AuditObjectType;
import com.workbuddy.common.security.audit.AuditEventPublisher;
import com.workbuddy.common.security.audit.AuditExpressionEvaluator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计切面单测：入参载荷、ID 求值、以及异常路径不落审计。
 */
class AuditAspectTest {

    private final AuditEventPublisher publisher = mock(AuditEventPublisher.class);

    private final AuditAspect aspect = new AuditAspect(new AuditExpressionEvaluator(), publisher);

    /**
     * 守护 {@code shouldPublishArgsPayloadAfterMethod}：成功后按入参 JSON 落审计。
     *
     * @throws Throwable 被拦截方法的异常
     */
    @Test
    void shouldPublishArgsPayloadAfterMethod() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint(new Object[] {9L, "admin"}, "ok");
        Audit audit = audit(AuditActionType.STORAGE_SWITCH, AuditObjectType.STORAGE_SOURCE, "#p0");

        assertEquals("ok", aspect.around(joinPoint, audit));
        verify(publisher).publish(eq(AuditActionType.STORAGE_SWITCH),
                eq(AuditObjectType.STORAGE_SOURCE.key()), eq(9L), anyString());
    }

    /**
     * 守护 {@code shouldPropagateExceptionWithoutAudit}：业务抛异常时不写审计。
     */
    @Test
    void shouldPropagateExceptionWithoutAudit() throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("boom"));
        Audit audit = audit(AuditActionType.CREATE, AuditObjectType.KNOWLEDGE_BASE, "#result");

        assertThrows(IllegalStateException.class, () -> aspect.around(joinPoint, audit));
        verify(publisher, never()).publish(any(), anyString(), any(), any());
    }

    /** 构造被拦截方法：指定入参与返回值 */
    private ProceedingJoinPoint joinPoint(Object[] args, Object result) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(args);
        when(joinPoint.proceed()).thenReturn(result);
        return joinPoint;
    }

    /** 构造审计声明替身 */
    private Audit audit(AuditActionType action, AuditObjectType objectType, String id) {
        Audit audit = mock(Audit.class);
        when(audit.action()).thenReturn(action);
        when(audit.objectType()).thenReturn(objectType);
        when(audit.id()).thenReturn(id);
        return audit;
    }
}
