package com.workbuddy.common.dto.request.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 用户联系方式三件套（真实姓名 / 邮箱 / 手机号）：几处请求 DTO 共用同一套字段与校验口径。
 *
 * <p>自助注册与管理员新增走 {@link UserCreateBaseDTO}（三件套 + 用户名 + 密码），
 * 本人改资料走 {@link ProfileUpdateDTO}（只有三件套）。抽到这里单点定义：
 * 改一处三处同步，也消掉这段平行样板（56 tokens，在更严的 CPD 阈值下会被判成重复代码）。
 */
@Data
public abstract class UserContactBaseDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 真实姓名 */
    @NotBlank(message = "真实姓名不能为空")
    @Size(max = 64, message = "真实姓名长度不能超过 64 位")
    private String displayName;

    /** 邮箱（可空，空串表示清空） */
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过 128 位")
    private String email;

    /** 手机号（可空，空串表示清空，允许数字与 + - ( ) 空格） */
    @Pattern(regexp = "^$|^[0-9+()\\- ]{6,32}$", message = "手机号格式不正确")
    private String phone;
}
