package com.workbuddy.biz.testkit;

import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.security.KnowledgeUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * 测试用登录上下文：给归属校验提供"当前登录用户"。
 *
 * <p>归属规则读的是 Spring Security 上下文里的 principal，单测里直接放一个进去，
 * 比给每个测试类加静态 mock 更贴近真实链路。
 */
public final class SecurityTestSupport {

    /** 普通用户 ID（与各测试里知识库的 user_id 对齐即"本人"） */
    public static final long VIEWER_ID = 1L;

    /** 另一个普通用户 ID（用于构造越权：不是知识库的归属者） */
    public static final long OTHER_USER_ID = 2L;

    private SecurityTestSupport() {
    }

    /** 以普通用户身份登录（本人） */
    public static void loginViewer() {
        login(VIEWER_ID, UserRole.USER);
    }

    /** 以另一个普通用户身份登录（越权场景） */
    public static void loginOtherUser() {
        login(OTHER_USER_ID, UserRole.USER);
    }

    /** 以管理员身份登录（归属校验对管理员放行） */
    public static void loginAdmin() {
        login(VIEWER_ID, UserRole.ADMIN);
    }

    /** 清空登录上下文（未登录场景） */
    public static void logout() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 放入指定身份的认证上下文。
     *
     * @param userId 用户 ID
     * @param role   角色
     */
    public static void login(long userId, UserRole role) {
        KnowledgeUser user = new KnowledgeUser();
        user.setId(userId);
        user.setUsername("u" + userId);
        user.setRole(role);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.getCode())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
