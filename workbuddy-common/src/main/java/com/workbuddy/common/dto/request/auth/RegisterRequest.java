package com.workbuddy.common.dto.request.auth;

import com.workbuddy.common.dto.request.user.UserCreateBaseDto;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 注册请求（自助注册，落库一律普通用户）。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RegisterRequest extends UserCreateBaseDto {

    @Serial
    private static final long serialVersionUID = 1L;
}
