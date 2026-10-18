package com.workbuddy.common.security;

/**
 * 授权表达式常量：把角色口径收在一处，避免注解与安全配置里散落字符串字面量。
 *
 * <p>权限值由 JwtAuthFilter 按 {@code "ROLE_" + 角色码值} 写入安全上下文，
 * 与 {@link com.workbuddy.common.enums.user.UserRole} 一一对应。
 */
public final class RoleExpressions {

    /** Spring Security 权限前缀 */
    public static final String ROLE_PREFIX = "ROLE_";

    /** 仅管理员 */
    public static final String ADMIN_ONLY = "hasAuthority('ROLE_ADMIN')";

    /** 管理员或普通用户（登录即可） */
    public static final String ANY_ROLE = "hasAnyAuthority('ROLE_ADMIN', 'ROLE_USER')";

    private RoleExpressions() {
    }
}
