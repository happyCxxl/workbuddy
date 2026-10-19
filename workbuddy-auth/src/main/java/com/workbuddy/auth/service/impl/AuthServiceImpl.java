package com.workbuddy.auth.service.impl;

import com.workbuddy.auth.db.WbUserDbService;
import com.workbuddy.auth.service.AuthService;
import com.workbuddy.auth.service.support.LoginAttemptGuard;
import com.workbuddy.auth.util.JwtUtil;
import com.workbuddy.common.domain.entity.WbUser;
import com.workbuddy.common.dto.request.auth.LoginDTO;
import com.workbuddy.common.dto.request.auth.RegisterDTO;
import com.workbuddy.common.dto.response.auth.LoginVO;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.enums.user.UserStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.AvatarUrlUtil;
import com.workbuddy.common.utils.ClientIpUtil;
import com.workbuddy.common.utils.NullUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;

/**
 * 认证服务实现。
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final WbUserDbService userDbService;

    private final PasswordEncoder passwordEncoder;

    private final JwtUtil jwtUtil;

    private final LoginAttemptGuard loginAttemptGuard;

    /**
     * 登录：先判失败阈值，再校验口令；失败按账号与 IP 两个维度计数，成功后清零账号维度。
     *
     * @param request 登录请求
     * @return 令牌与用户信息
     */
    @Override
    public LoginVO login(LoginDTO request) {
        String ip = ClientIpUtil.getClientIp();
        // 已锁定则直接拒绝：不查库、不跑 BCrypt（BCrypt 是慢哈希，放任尝试等于给对方一个 CPU 放大器）
        ThrowUtil.throwIf(loginAttemptGuard.isLocked(request.getUsername(), ip),
                ErrorCode.LOGIN_TOO_FREQUENT);
        WbUser user = userDbService.findActiveByUsername(request.getUsername());
        boolean credentialsMatch = NullUtil.isNotNull(user)
                && passwordEncoder.matches(request.getPassword(), user.getPassword());
        ThrowUtil.throwIf(!credentialsMatch, ErrorCode.LOGIN_FAILED,
                () -> loginAttemptGuard.recordFailure(request.getUsername(), ip));
        loginAttemptGuard.clearAccount(request.getUsername(), ip);
        // 角色与令牌版本写入令牌载荷：角色供鉴权判定，版本供失效校验
        UserRole role = UserRole.of(user.getRole());
        Integer tokenVersion = Objects.requireNonNullElse(user.getTokenVersion(), 0);
        Duration ttl = request.isRemember() ? jwtUtil.rememberTtl() : jwtUtil.standardTtl();
        LoginVO vo = new LoginVO();
        vo.setToken(jwtUtil.sign(user.getId(), user.getUsername(), role, tokenVersion, ttl));
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setDisplayName(user.getDisplayName());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setAvatar(AvatarUrlUtil.urlOrNull(user.getAvatar()));
        vo.setStatus(user.getStatus());
        vo.setRole(user.getRole());
        vo.setTokenVersion(user.getTokenVersion());
        return vo;
    }

    /**
     * 注册：用户名唯一，口令加密后入库。
     *
     * @param request 注册请求
     */
    @Override
    public void register(RegisterDTO request) {
        ThrowUtil.throwIf(userDbService.existsByUsername(request.getUsername()),
                ErrorCode.USERNAME_EXISTS);
        WbUser user = new WbUser();
        user.setUsername(request.getUsername());
        user.setDisplayName(request.getDisplayName());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setStatus(UserStatus.ENABLED.getCode());
        // 自助注册一律为普通用户，管理员由既有管理员在用户管理中调整
        user.setRole(UserRole.USER.getCode());
        userDbService.save(user);
    }
}
