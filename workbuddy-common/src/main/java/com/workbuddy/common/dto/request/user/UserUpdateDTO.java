package com.workbuddy.common.dto.request.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 编辑用户请求（管理员操作）。
 *
 * <p>用户名不可修改：它是登录凭据与留痕口径，改动会让历史记录对不上。
 * 密码不传表示不重置；角色与状态不传表示保持不变。
 *
 * @author cxxl
 */
@Data
public class UserUpdateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 真实姓名（不传表示不变） */
    @Size(max = 64, message = "真实姓名长度不能超过 64 位")
    @Pattern(regexp = ".*\\S.*", message = "真实姓名不能为空")
    private String displayName;

    /** 邮箱（不传表示不变，传空串表示清空） */
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过 128 位")
    private String email;

    /** 手机号（不传表示不变，传空串表示清空） */
    @Pattern(regexp = "^$|^[0-9+()\\- ]{6,32}$", message = "手机号格式不正确")
    private String phone;

    /** 新密码（明文，8-10 位，不传表示不重置） */
    @Size(min = 8, max = 10, message = "密码长度须为 8-10 位")
    private String password;

    /** 角色码值：ADMIN / USER（不传表示不变） */
    private String role;

    /** 状态：1 启用 / 0 停用（不传表示不变） */
    private Integer status;
}
