package com.workbuddy.common.enums.user;

import com.workbuddy.common.utils.NullUtil;
import lombok.Getter;

/**
 * 用户角色：决定可见菜单与可访问接口的范围。
 *
 * <p>落库为码值字符串（kb_user.role）；令牌载荷同名键携带角色，供网关侧鉴权。
 *
 * @author cxxl
 */
@Getter
public enum UserRole {

    /** 管理员：可见用户管理等系统菜单，可访问仅管理员接口 */
    ADMIN("ADMIN"),

    /** 普通用户：仅业务功能，不可访问管理类接口 */
    USER("USER");

    private final String code;

    UserRole(String code) {
        this.code = code;
    }

    /**
     * 按码值解析角色（大小写不敏感）。
     *
     * @param code 角色码值（可空/非法）
     * @return 匹配的角色；无法识别时回落 {@link #USER}，避免构造出「无角色」的用户
     */
    public static UserRole of(String code) {
        if (NullUtil.isNotNull(code)) {
            for (UserRole role : values()) {
                if (role.code.equalsIgnoreCase(code.trim())) {
                    return role;
                }
            }
        }
        return USER;
    }
}
