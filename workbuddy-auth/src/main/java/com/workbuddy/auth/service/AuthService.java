package com.workbuddy.auth.service;

import com.workbuddy.common.dto.request.auth.LoginDTO;
import com.workbuddy.common.dto.request.auth.RegisterDTO;
import com.workbuddy.common.dto.response.auth.LoginVO;

/**
 * 认证服务：登录与注册。
 *
 * <p>用户账号的查询与维护见 {@link UserService}。
 */
public interface AuthService {

    /**
     * 登录：校验用户名密码，签发访问令牌。
     *
     * @param request 登录请求
     * @return 登录结果（含令牌）
     */
    LoginVO login(LoginDTO request);

    /**
     * 注册：用户名唯一校验，密码 BCrypt 加密后落库。
     *
     * @param request 注册请求
     */
    void register(RegisterDTO request);
}
