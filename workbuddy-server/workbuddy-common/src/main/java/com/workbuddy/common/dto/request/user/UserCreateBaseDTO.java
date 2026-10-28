package com.workbuddy.common.dto.request.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 创建账号类请求的公共字段：自助注册与管理员新增共用。
 *
 * <p>联系方式三件套（真实姓名 / 邮箱 / 手机号）及其校验在父类 {@link UserContactBaseDTO}，
 * 这里只加"创建"特有的两项：登录用户名与密码。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class UserCreateBaseDTO extends UserContactBaseDTO {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 登录用户名（3-32 位，全局唯一） */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度须为 3-32 位")
    private String username;

    /** 密码（8-10 位） */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 10, message = "密码长度须为 8-10 位")
    private String password;
}
