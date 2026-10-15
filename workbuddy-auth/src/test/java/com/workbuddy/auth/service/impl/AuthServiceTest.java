package com.workbuddy.auth.service.impl;

import com.workbuddy.auth.db.UserDbService;
import com.workbuddy.auth.service.support.LoginAttemptGuard;
import com.workbuddy.auth.util.JwtUtil;
import com.workbuddy.common.domain.entity.User;
import com.workbuddy.common.dto.request.auth.LoginRequest;
import com.workbuddy.common.dto.request.auth.RegisterRequest;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证服务测试。
 *
 * @author cxxl
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Duration STANDARD_TTL = Duration.ofDays(1);

    private static final Duration REMEMBER_TTL = Duration.ofDays(30);

    @Mock
    private UserDbService userDbService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

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

    @Test
    void loginShouldFallbackToUserRoleWhenRoleMissing() {
        User plain = adminUser();
        plain.setRole(null);
        when(userDbService.findActiveByUsername("admin")).thenReturn(plain);
        when(passwordEncoder.matches("pass123456", "encoded")).thenReturn(true);
        when(jwtUtil.sign(1L, "admin", UserRole.USER, 0, STANDARD_TTL)).thenReturn("jwt-token");

        authService.login(loginRequest("admin", "pass123456"));

        // 库中角色为空时回落普通用户，且回落后的角色进入令牌载荷
        verify(jwtUtil).sign(1L, "admin", UserRole.USER, 0, STANDARD_TTL);
    }

    @Test
    void loginShouldCarryCurrentTokenVersion() {
        User user = adminUser();
        user.setTokenVersion(5);
        when(userDbService.findActiveByUsername("admin")).thenReturn(user);
        when(passwordEncoder.matches("pass123456", "encoded")).thenReturn(true);
        when(jwtUtil.sign(1L, "admin", UserRole.ADMIN, 5, STANDARD_TTL)).thenReturn("jwt-token");

        LoginVO vo = authService.login(loginRequest("admin", "pass123456"));

        assertEquals("jwt-token", vo.getToken());
    }

    @Test
    void loginShouldThrowWhenUserNotFound() {
        when(userDbService.findActiveByUsername("nobody")).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> authService.login(loginRequest("nobody", "pass123456")));

        assertEquals(ErrorCode.LOGIN_FAILED, e.getErrorCode());
    }

    @Test
    void loginShouldThrowWhenPasswordMismatch() {
        when(userDbService.findActiveByUsername("admin")).thenReturn(adminUser());
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> authService.login(loginRequest("admin", "wrong")));

        assertEquals(ErrorCode.LOGIN_FAILED, e.getErrorCode());
    }

    @Test
    void registerShouldThrowWhenUsernameExists() {
        when(userDbService.existsByUsername("admin")).thenReturn(true);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> authService.register(registerRequest()));

        assertEquals(ErrorCode.USERNAME_EXISTS, e.getErrorCode());
        verify(userDbService, never()).save(any());
    }

    @Test
    void registerShouldEncodePasswordAndSave() {
        when(userDbService.existsByUsername("admin")).thenReturn(false);
        when(passwordEncoder.encode("pass123456")).thenReturn("encoded");

        authService.register(registerRequest());

        verify(passwordEncoder).encode("pass123456");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userDbService).save(captor.capture());
        // 自助注册一律普通用户，不得自行获得管理员
        assertEquals(UserRole.USER.getCode(), captor.getValue().getRole());
        assertEquals("张三", captor.getValue().getDisplayName());
        assertEquals("zhangsan@example.com", captor.getValue().getEmail());
    }

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

    @Test
    void loginShouldClearAccountCounterOnSuccess() {
        when(userDbService.findActiveByUsername("admin")).thenReturn(adminUser());
        when(passwordEncoder.matches("pass123456", "encoded")).thenReturn(true);
        when(jwtUtil.sign(1L, "admin", UserRole.ADMIN, 0, STANDARD_TTL)).thenReturn("jwt-token");

        authService.login(loginRequest("admin", "pass123456"));

        verify(loginAttemptGuard).clearAccount("admin", "10.0.0.1");
        verify(loginAttemptGuard, never()).recordFailure(any(), any());
    }

    @Test
    void loginShouldIssueLongLivedTokenWhenRememberMe() {
        LoginRequest request = loginRequest("admin", "pass123456");
        request.setRemember(true);
        when(userDbService.findActiveByUsername("admin")).thenReturn(adminUser());
        when(passwordEncoder.matches("pass123456", "encoded")).thenReturn(true);
        when(jwtUtil.sign(1L, "admin", UserRole.ADMIN, 0, REMEMBER_TTL)).thenReturn("long-token");

        LoginVO vo = authService.login(request);

        assertEquals("long-token", vo.getToken());
        verify(jwtUtil).sign(1L, "admin", UserRole.ADMIN, 0, REMEMBER_TTL);
    }

    private LoginRequest loginRequest(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }

    private RegisterRequest registerRequest() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("admin");
        request.setDisplayName("张三");
        request.setEmail("zhangsan@example.com");
        request.setPassword("pass123456");
        return request;
    }

    private User adminUser() {
        User user = new User();
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
