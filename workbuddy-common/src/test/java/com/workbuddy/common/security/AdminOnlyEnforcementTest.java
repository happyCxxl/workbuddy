package com.workbuddy.common.security;

import com.workbuddy.common.annotation.AdminOnly;
import com.workbuddy.common.enums.user.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 接口权限注解生效性测试：以真实 Spring AOP 代理验证 {@link AdminOnly} 是否拦住调用。
 *
 * <p>本测试同时回答「要不要自己写切面」：不需要。{@code @EnableMethodSecurity} 会注册
 * {@code preAuthorizeAuthorizationMethodInterceptor} 这个基础设施 Bean 作为切面，
 * 它借助 Spring Core 的 {@code MergedAnnotations} 解析注解，能识别把
 * {@code @PreAuthorize} 作为元注解的 {@link AdminOnly}。
 *
 * @author cxxl
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AdminOnlyEnforcementTest.TestConfig.class)
class AdminOnlyEnforcementTest {

    @Configuration
    @EnableMethodSecurity
    @ImportAutoConfiguration(AopAutoConfiguration.class)
    static class TestConfig {

        @Bean
        AdminApi adminApi() {
            return new AdminApi();
        }
    }

    /** 被保护的目标：类上标注仅管理员 */
    @AdminOnly
    static class AdminApi {

        String listUsers() {
            return "ok";
        }
    }

    @Autowired
    private AdminApi adminApi;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminShouldPassAnnotationGuard() {
        authenticateAs(UserRole.ADMIN);

        assertDoesNotThrow(() -> adminApi.listUsers(), "ROLE_ADMIN 调用 @AdminOnly 方法应放行");
    }

    @Test
    void plainUserShouldBeDeniedByAnnotationGuard() {
        authenticateAs(UserRole.USER);

        assertThrows(AccessDeniedException.class, () -> adminApi.listUsers(),
                "ROLE_USER 调用 @AdminOnly 方法应被拒绝");
    }

    @Test
    void anonymousShouldBeDeniedByAnnotationGuard() {
        SecurityContextHolder.clearContext();

        // 匿名调用时表达式求值取不到认证对象，抛 AuthenticationCredentialsNotFoundException
        // （它是 AccessDeniedException 的子类）
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> adminApi.listUsers(),
                "未认证调用 @AdminOnly 方法应被拒绝");
    }

    private void authenticateAs(UserRole role) {
        Authentication authentication = new UsernamePasswordAuthenticationToken("tester", null,
                List.of(new SimpleGrantedAuthority(RoleExpressions.ROLE_PREFIX + role.getCode())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
