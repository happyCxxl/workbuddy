package com.workbuddy.common.annotation;

import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.knowledge.AuditObjectType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解：标在会改变业务状态的 public 方法上，由审计切面统一落一条审计记录。
 *
 * <p>记录内容只从方法签名推导：动作与对象类型由注解声明，对象 ID 由 {@link #id()} 求值，
 * 载荷是方法入参的 JSON。切面不查库、不认识任何业务实体，因此入参里不要放敏感字段
 * （密码、令牌等）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audit {

    /**
     * 审计动作。
     *
     * @return 动作类型
     */
    AuditActionType action();

    /**
     * 审计对象类型（落 object_type 列）。
     *
     * @return 对象类型
     */
    AuditObjectType objectType();

    /**
     * 对象 ID 的 SpEL 表达式：{@code #p0}、{@code #p1} 引用入参，{@code #result} 引用返回值。
     *
     * @return 表达式；求值结果必须是非空整数
     */
    String id();
}
