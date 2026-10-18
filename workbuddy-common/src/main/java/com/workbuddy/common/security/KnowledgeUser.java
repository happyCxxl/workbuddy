package com.workbuddy.common.security;

import com.workbuddy.common.enums.user.UserRole;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * 用户模型。
 */
@Data
public class KnowledgeUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    private Long id;

    /** 登录用户名 */
    private String username;

    /** 角色（令牌载荷携带；旧令牌无此字段时回落 USER） */
    private UserRole role = UserRole.USER;

    /** 令牌版本（令牌载荷携带；用于比对库值判断令牌是否已失效） */
    private Integer tokenVersion;

    /** 令牌到期时刻（令牌载荷携带；供补签令牌沿用剩余时长） */
    private Instant expiresAt;
}
