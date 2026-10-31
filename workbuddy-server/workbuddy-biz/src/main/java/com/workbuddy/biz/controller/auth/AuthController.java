package com.workbuddy.biz.controller.auth;

import com.workbuddy.auth.service.AuthService;
import com.workbuddy.auth.support.AuthCookieSupport;
import com.workbuddy.auth.support.JwtTokenSupport;
import com.workbuddy.common.web.R;
import com.workbuddy.common.dto.request.auth.LoginDTO;
import com.workbuddy.common.dto.request.auth.RegisterDTO;
import com.workbuddy.common.dto.response.auth.LoginVO;
import com.workbuddy.common.annotation.RateLimit;
import jakarta.servlet.http.HttpServletResponse;
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

    private final AuthCookieSupport authCookieSupport;

    private final JwtTokenSupport jwtTokenSupport;

    /**
     * 登录：令牌写入 HttpOnly Cookie（响应体不回传令牌），接口按 IP 限流。
     *
     * @param request  登录请求
     * @param response HTTP 响应（写 Cookie）
     * @return 用户信息（令牌字段置空）
     */
    @PostMapping("/login")
    @RateLimit()
    public R<LoginVO> login(@Valid @RequestBody LoginDTO request, HttpServletResponse response) {
        LoginVO vo = authService.login(request);
        if (request.isRemember()) {
            authCookieSupport.write(response, vo.getToken(), jwtTokenSupport.rememberTtl());
        } else {
            // 未勾选「记住我」：会话 Cookie，浏览器关闭即失效
            authCookieSupport.writeSession(response, vo.getToken());
        }
        // 令牌只走 HttpOnly Cookie，避免脚本可读
        vo.setToken(null);
        return R.ok(vo);
    }

    /**
     * 注册新用户。
     *
     * @param request 注册请求
     * @return 恒为成功响应
     */
    @PostMapping("/register")
    public R<Void> register(@Valid @RequestBody RegisterDTO request) {
        authService.register(request);
        return R.ok(null, "注册成功");
    }

    /**
     * 退出登录：递增令牌版本并清除登录 Cookie。
     *
     * @param response HTTP 响应（清 Cookie）
     * @return 恒为成功响应
     */
    @PostMapping("/logout")
    public R<Void> logout(HttpServletResponse response) {
        authService.logout();
        authCookieSupport.clear(response);
        return R.ok(null, "已退出登录");
    }
}
