package com.workbuddy.common.annotation;

import com.workbuddy.common.security.RoleExpressions;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 仅管理员可访问：接口级权限声明。
 *
 * <p>等价于 {@code @PreAuthorize("hasAuthority('ROLE_ADMIN')")}，把角色口径收敛到
 * {@link RoleExpressions#ADMIN_ONLY}，避免各接口散落字符串字面量。
 *
 * <p>使用前提：启动类所在应用需开启方法级安全（{@code @EnableMethodSecurity}）。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize(RoleExpressions.ADMIN_ONLY)
public @interface AdminOnly {
}
