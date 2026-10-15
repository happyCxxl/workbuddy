package com.workbuddy.common.security;

import com.workbuddy.common.annotation.AdminOnly;
import com.workbuddy.common.enums.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口权限注解契约测试：验证 {@code @AdminOnly} 挂上了 {@code @PreAuthorize}，
 * 且「ROLE_ADMIN 放行 / ROLE_USER 拒绝」的判定成立。
 *
 * <p>注解在运行期是否真的拦住请求，由接口级测试覆盖；这里锁定注解与角色口径。
 *
 * @author cxxl
 */
class AdminOnlyContractTest {

    @Test
    void adminOnlyShouldBeMetaAnnotatedWithPreAuthorize() {
        PreAuthorize preAuthorize = findPreAuthorize();

        assertNotNull(preAuthorize, "@AdminOnly 必须元注解 @PreAuthorize，否则方法级安全不会生效");
        assertEquals(RoleExpressions.ADMIN_ONLY, preAuthorize.value());
    }

    @Test
    void adminOnlyExpressionShouldReferenceAdminAuthority() {
        // 表达式必须基于 ROLE_ 前缀的权限值，与 JwtAuthFilter 写入的权限保持一致
        assertTrue(RoleExpressions.ADMIN_ONLY.contains(RoleExpressions.ROLE_PREFIX + UserRole.ADMIN.getCode()),
                "@AdminOnly 表达式应引用 ROLE_ADMIN");
    }

    @Test
    void filterShouldGrantAuthorityMatchingExpression() {
        // 过滤器写入的权限串必须能被 @AdminOnly 的表达式命中
        String granted = RoleExpressions.ROLE_PREFIX + UserRole.ADMIN.getCode();

        assertTrue(RoleExpressions.ADMIN_ONLY.contains("'" + granted + "'"),
                "过滤器授权串与注解表达式不一致：granted=" + granted);
    }

    @Test
    void authorityManagerShouldGrantAdminAndRejectPlainUser() {
        AuthorityAuthorizationManager<Object> manager = AuthorityAuthorizationManager
                .<Object>hasAuthority(RoleExpressions.ROLE_PREFIX + UserRole.ADMIN.getCode());

        boolean adminGranted = manager.check(() -> authentication(UserRole.ADMIN), new Object()).isGranted();
        boolean userGranted = manager.check(() -> authentication(UserRole.USER), new Object()).isGranted();

        assertTrue(adminGranted, "ROLE_ADMIN 应被放行");
        assertFalse(userGranted, "ROLE_USER 应被拒绝");
    }

    private Authentication authentication(UserRole role) {
        return new UsernamePasswordAuthenticationToken("tester", null,
                List.of(new SimpleGrantedAuthority(RoleExpressions.ROLE_PREFIX + role.getCode())));
    }

    private PreAuthorize findPreAuthorize() {
        return Arrays.stream(AdminOnly.class.getAnnotations())
                .filter(PreAuthorize.class::isInstance)
                .map(PreAuthorize.class::cast)
                .findFirst()
                .orElse(null);
    }
}
