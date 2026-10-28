package com.workbuddy.common.utils;

import com.workbuddy.common.security.KnowledgeUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 安全上下文工具。
 */
public final class SecurityUtil {

    private SecurityUtil() {
    }

    /**
     * 取当前登录用户。
     *
     * @return 当前登录用户；无认证上下文时为 null
     */
    public static KnowledgeUser getUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (NullUtil.isNotNull(authentication) && authentication.getPrincipal() instanceof KnowledgeUser) {
            return (KnowledgeUser) authentication.getPrincipal();
        }
        return null;
    }
}
