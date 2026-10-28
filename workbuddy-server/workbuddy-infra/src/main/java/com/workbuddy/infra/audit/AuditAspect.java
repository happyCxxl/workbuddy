package com.workbuddy.infra.audit;

import com.workbuddy.common.annotation.Audit;
import com.workbuddy.common.security.audit.AuditEventPublisher;
import com.workbuddy.common.security.audit.AuditExpressionEvaluator;
import com.workbuddy.common.utils.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 审计切面：拦截 {@link Audit} 标注的方法，成功后落一条操作审计。
 *
 * <p>审计内容只由方法签名决定：动作与对象类型取注解，对象 ID 取 {@code id} 表达式，
 * 载荷是入参 JSON。切面不查库、不认识任何业务实体。
 *
 * <p>顺序排在事务增强内层（见 {@code TransactionOrderConfig}），审计写入与业务同事务：
 * 方法抛异常时不写审计，审计写不进去时业务一并回滚。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditExpressionEvaluator auditExpressionEvaluator;

    private final AuditEventPublisher auditEventPublisher;

    /**
     * 审计切面：方法成功返回后求 ID 并发布审计事件。
     *
     * @param joinPoint 被拦截的方法
     * @param audit     方法上的审计声明
     * @return 原方法的返回值
     * @throws Throwable 原方法抛出的异常；审计 ID 求不出来时抛非法状态
     */
    @Around("@annotation(audit)")
    public Object around(ProceedingJoinPoint joinPoint, Audit audit) throws Throwable {
        Object result = joinPoint.proceed();
        Long objectId = auditExpressionEvaluator.evaluateId(audit.id(), joinPoint.getArgs(), result);
        auditEventPublisher.publish(audit.action(), audit.objectType().key(), objectId,
                JsonUtil.toJsonStr(joinPoint.getArgs()));
        log.info("===> AuditAspect 审计落库, action={}, objectType={}, objectId={}",
                audit.action(), audit.objectType().key(), objectId);
        return result;
    }
}
