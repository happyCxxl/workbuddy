package com.workbuddy.biz.controller.auth;

import com.workbuddy.auth.service.AuthService;
import com.workbuddy.common.web.R;
import com.workbuddy.common.dto.request.auth.LoginDTO;
import com.workbuddy.common.dto.request.auth.RegisterDTO;
import com.workbuddy.common.dto.response.auth.LoginVO;
import com.workbuddy.common.annotation.RateLimit;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：登录与注册。
 *
 * <p>用户账号的查询与维护见 {@link UserController}。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 登录 */
    @PostMapping("/login")
    @RateLimit()
    public R<LoginVO> login(@Valid @RequestBody LoginDTO request) {
        return R.ok(authService.login(request));
    }

    /** 注册 */
    @PostMapping("/register")
    public R<Void> register(@Valid @RequestBody RegisterDTO request) {
        authService.register(request);
        return R.ok(null, "注册成功");
    }
}
