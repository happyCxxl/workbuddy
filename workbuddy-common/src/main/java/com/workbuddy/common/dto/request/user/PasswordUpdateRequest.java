package com.workbuddy.common.dto.request.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 修改密码请求（本人操作）。
 *
 * <p>当前密码用于校验身份；新密码长度须为 8-10 位且不得与当前密码相同。
 *
 * @author cxxl
 */
@Data
public class PasswordUpdateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 当前密码 */
    @NotBlank(message = "当前密码不能为空")
    private String oldPassword;

    /** 新密码（8-10 位） */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 10, message = "密码长度须为 8-10 位")
    private String newPassword;
}
