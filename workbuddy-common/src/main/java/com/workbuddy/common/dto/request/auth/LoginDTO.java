package com.workbuddy.common.dto.request.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求。
 *
 * @author cxxl
 */
@Data
public class LoginDTO {

    /** 登录用户名 */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 密码 */
    @NotBlank(message = "密码不能为空")
    private String password;

    /** 是否「记住我」：true 下发长期令牌，false 下发短期令牌（不传按 false） */
    private boolean remember;
}
