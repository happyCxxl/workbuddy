package com.workbuddy.auth.service.impl;

import com.workbuddy.auth.db.WbUserDbService;
import com.workbuddy.auth.service.support.LoginAttemptGuard;
import com.workbuddy.auth.support.JwtTokenSupport;
import com.workbuddy.common.domain.entity.WbUser;
import com.workbuddy.common.dto.request.auth.LoginDTO;
import com.workbuddy.common.dto.request.auth.RegisterDTO;
import com.workbuddy.common.dto.response.auth.LoginVO;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证服务测试。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Duration STANDARD_TTL = Duration.ofDays(1);

    private static final Duration REMEMBER_TTL = Duration.ofDays(30);

    @Mock
    private WbUserDbService userDbService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenSupport jwtUtil;

    @Mock
    private LoginAttemptGuard loginAttemptGuard;

    @InjectMocks
    private AuthServiceImpl authService;

    @BeforeEach
    void stubConfiguredTtls() {
        lenient().when(jwtUtil.standardTtl()).thenReturn(STANDARD_TTL);
        lenient().when(jwtUtil.rememberTtl()).thenReturn(REMEMBER_TTL);
    }

    @BeforeEach
    void stubRequestContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 守护 {@code loginShouldReturnTokenWhenCredentialsMatch}：该场景行为改动即失败。
     */
    @Test
    void loginShouldReturnTokenWhenCredentialsMatch() {
        when(userDbService.findActiveByUsername("admin")).thenReturn(adminUser());
        when(passwordEncoder.matches("pass123456", "encoded")).thenReturn(true);
        when(jwtUtil.sign(1L, "admin", UserRole.ADMIN, 0, STANDARD_TTL)).thenReturn("jwt-token");

        LoginVO vo = authService.login(loginRequest("admin", "pass123456"));

        assertEquals("jwt-token", vo.getToken());
        assertEquals("管理员", vo.getDisplayName());
        assertEquals("admin@example.com", vo.getEmail());
        verify(jwtUtil).sign(1L, "admin", UserRole.ADMIN, 0, STANDARD_TTL);
    }

    /**
     * 守护 {@code loginShouldFallbackToUserRoleWhenRoleMissing}：该场景行为改动即失败。
     */
    @Test
    void loginShouldFallbackToUserRoleWhenRoleMissing() {
        WbUser plain = adminUser();
        plain.setRole(null);
        when(userDbService.findActiveByUsername("admin")).thenReturn(plain);
        when(passwordEncoder.matches("pass123456", "encoded")).thenReturn(true);
        when(jwtUtil.sign(1L, "admin", UserRole.USER, 0, STANDARD_TTL)).thenReturn("jwt-token");

        LoginVO vo = authService.login(loginRequest("admin", "pass123456"));

        // 库中角色为空时回落普通用户，且回落后的角色进入令牌载荷
        assertEquals("jwt-token", vo.getToken());
        // 回落只影响令牌载荷，VO 仍如实反映库中角色为空
        assertNull(vo.getRole());
        verify(jwtUtil).sign(1L, "admin", UserRole.USER, 0, STANDARD_TTL);
    }

    /**
     * 守护 {@code loginShouldCarryCurrentTokenVersion}：该场景行为改动即失败。
     */
    @Test
    void loginShouldCarryCurrentTokenVersion() {
        WbUser user = adminUser();
        user.setTokenVersion(5);
        when(userDbService.findActiveByUsername("admin")).thenReturn(user);
        when(passwordEncoder.matches("pass123456", "encoded")).thenReturn(true);
        when(jwtUtil.sign(1L, "admin", UserRole.ADMIN, 5, STANDARD_TTL)).thenReturn("jwt-token");

        LoginVO vo = authService.login(loginRequest("admin", "pass123456"));

        assertEquals("jwt-token", vo.getToken());
    }

    /**
     * 守护 {@code loginShouldThrowWhenUserNotFound}：该场景行为改动即失败。
     */
    @Test
    void loginShouldThrowWhenUserNotFound() {
        when(userDbService.findActiveByUsername("nobody")).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> authService.login(loginRequest("nobody", "pass123456")));

        assertEquals(ErrorCode.LOGIN_FAILED, e.getErrorCode());
    }

    /**
     * 守护 {@code loginShouldThrowWhenPasswordMismatch}：该场景行为改动即失败。
     */
    @Test
    void loginShouldThrowWhenPasswordMismatch() {
        when(userDbService.findActiveByUsername("admin")).thenReturn(adminUser());
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> authService.login(loginRequest("admin", "wrong")));

        assertEquals(ErrorCode.LOGIN_FAILED, e.getErrorCode());
    }

    /**
     * 守护 {@code registerShouldThrowWhenUsernameExists}：该场景行为改动即失败。
     */
    @Test
    void registerShouldThrowWhenUsernameExists() {
        when(userDbService.existsByUsername("admin")).thenReturn(true);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> authService.register(registerRequest()));

        assertEquals(ErrorCode.USERNAME_EXISTS, e.getErrorCode());
        verify(userDbService, never()).save(any());
    }

    /**
     * 守护 {@code registerShouldEncodePasswordAndSave}：该场景行为改动即失败。
     */
    @Test
    void registerShouldEncodePasswordAndSave() {
        when(userDbService.existsByUsername("admin")).thenReturn(false);
        when(passwordEncoder.encode("pass123456")).thenReturn("encoded");

        authService.register(registerRequest());

        verify(passwordEncoder).encode("pass123456");
        ArgumentCaptor<WbUser> captor = ArgumentCaptor.forClass(WbUser.class);
        verify(userDbService).save(captor.capture());
        // 自助注册一律普通用户，不得自行获得管理员
        assertEquals(UserRole.USER.getCode(), captor.getValue().getRole());
        assertEquals("张三", captor.getValue().getDisplayName());
        assertEquals("zhangsan@example.com", captor.getValue().getEmail());
    }

    /**
     * 守护 {@code loginShouldRejectWhenLockedWithoutTouchingDatabase}：该场景行为改动即失败。
     */
    @Test
    void loginShouldRejectWhenLockedWithoutTouchingDatabase() {
        when(loginAttemptGuard.isLocked("admin", "10.0.0.1")).thenReturn(true);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> authService.login(loginRequest("admin", "pass123456")));

        assertEquals(ErrorCode.LOGIN_TOO_FREQUENT, e.getErrorCode());
        // 锁定期内不查库也不比对密码（BCrypt 是慢哈希，不能让它继续跑）
        verify(userDbService, never()).findActiveByUsername(any());
        verify(passwordEncoder, never()).matches(any(), any());
    }

    /**
     * 守护 {@code loginShouldRecordFailureWhenPasswordMismatch}：该场景行为改动即失败。
     */
    @Test
    void loginShouldRecordFailureWhenPasswordMismatch() {
        when(userDbService.findActiveByUsername("admin")).thenReturn(adminUser());
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> authService.login(loginRequest("admin", "wrong")));

        assertEquals(ErrorCode.LOGIN_FAILED, e.getErrorCode());
        verify(loginAttemptGuard).recordFailure("admin", "10.0.0.1");
        verify(loginAttemptGuard, never()).clearAccount(any(), any());
    }

    /**
     * 守护 {@code loginShouldClearAccountCounterOnSuccess}：该场景行为改动即失败。
     */
    @Test
    void loginShouldClearAccountCounterOnSuccess() {
        when(userDbService.findActiveByUsername("admin")).thenReturn(adminUser());
        when(passwordEncoder.matches("pass123456", "encoded")).thenReturn(true);
        when(jwtUtil.sign(1L, "admin", UserRole.ADMIN, 0, STANDARD_TTL)).thenReturn("jwt-token");

        LoginVO vo = authService.login(loginRequest("admin", "pass123456"));

        assertEquals("jwt-token", vo.getToken());
        assertEquals(UserRole.ADMIN.getCode(), vo.getRole());
        verify(loginAttemptGuard).clearAccount("admin", "10.0.0.1");
        verify(loginAttemptGuard, never()).recordFailure(any(), any());
    }

    /**
     * 守护 {@code loginShouldIssueLongLivedTokenWhenRememberMe}：该场景行为改动即失败。
     */
    @Test
    void loginShouldIssueLongLivedTokenWhenRememberMe() {
        LoginDTO request = loginRequest("admin", "pass123456");
        request.setRemember(true);
        when(userDbService.findActiveByUsername("admin")).thenReturn(adminUser());
        when(passwordEncoder.matches("pass123456", "encoded")).thenReturn(true);
        when(jwtUtil.sign(1L, "admin", UserRole.ADMIN, 0, REMEMBER_TTL)).thenReturn("long-token");

        LoginVO vo = authService.login(request);

        assertEquals("long-token", vo.getToken());
        verify(jwtUtil).sign(1L, "admin", UserRole.ADMIN, 0, REMEMBER_TTL);
    }

    private LoginDTO loginRequest(String username, String password) {
        LoginDTO request = new LoginDTO();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    private RegisterDTO registerRequest() {
        RegisterDTO request = new RegisterDTO();
        request.setUsername("admin");
        request.setDisplayName("张三");
        request.setEmail("zhangsan@example.com");
        request.setPassword("pass123456");
        return request;
    }

    private WbUser adminUser() {
        WbUser user = new WbUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setDisplayName("管理员");
        user.setEmail("admin@example.com");
        user.setPassword("encoded");
        user.setStatus(1);
        user.setRole(UserRole.ADMIN.getCode());
        return user;
    }
}
