package com.workbuddy.common.enums.user;

import com.workbuddy.common.utils.NullUtil;
import lombok.Getter;

/**
 * 用户账号状态（kb_user.status，TINYINT 码值）。
 * 1 启用（ENABLED）/ 0 停用（DISABLED）；逻辑删除由 del_flag 承载，与本枚举无关。
 *
 * @author cxxl
 */
@Getter
public enum UserStatus {

    /** 启用：可登录 */
    ENABLED(1),

    /** 停用：不可登录，已签发令牌在下次请求时失效 */
    DISABLED(0);

    private final int code;

    UserStatus(int code) {
        this.code = code;
    }

    /**
     * 按码值转枚举；空值与未知码值返回 null。
     *
     * @param code 状态码值（可空/非法）
     * @return 匹配的状态；无法识别返回 null
     */
    public static UserStatus of(Integer code) {
        if (NullUtil.isNull(code)) {
            return null;
        }
        for (UserStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }

    /**
     * 码值是否为启用状态；空值与未知码值都按停用处理。
     *
     * @param code 状态码值
     * @return 启用返回 true
     */
    public static boolean isEnabled(Integer code) {
        return ENABLED == of(code);
    }
}
