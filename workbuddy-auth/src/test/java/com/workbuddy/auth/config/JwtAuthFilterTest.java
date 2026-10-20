package com.workbuddy.auth.config;

import com.workbuddy.auth.db.WbUserDbService;
import com.workbuddy.auth.support.JwtTokenSupport;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.security.KnowledgeUser;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JWT 认证过滤器单测。
 *
 * <p>守护令牌解析与失效口径：无令牌放行、有效令牌写入 ROLE_ 权限、
 * 版本不匹配 / 用户被删 / 解析失败一律清空上下文；写错会让旧令牌继续有效或误放匿名。
 */
@org.junit.jupiter.api.extension.ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock
    private JwtTokenSupport jwtUtil;

    @Mock
    private WbUserDbService userDbService;

    private JwtAuthFilter filter;

    /**
     * 每个用例重建过滤器并清空安全上下文。
     */
    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(jwtUtil, userDbService);
        SecurityContextHolder.clearContext();
    }

    /**
     * 收尾清空安全上下文，避免污染其他用例。
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 无 Authorization 头：不解析令牌，直接放行。
     *
     * <p>守护 {@code doFilterInternal} 的无令牌分支。
     */
    @Test
    void noHeaderShouldPassThroughWithoutParsing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(jwtUtil, never()).parse(org.mockito.ArgumentMatchers.any());
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * 有效令牌且版本一致：写入用户与 ROLE_ 权限。
     *
     * <p>守护 {@code doFilterInternal} 的成功分支：角色前缀或上下文写入错会让鉴权失效。
     */
    @Test
    void validTokenShouldPopulateSecurityContext() throws Exception {
        KnowledgeUser user = new KnowledgeUser();
        user.setId(7L);
        user.setRole(UserRole.ADMIN);
        user.setTokenVersion(2);
        when(jwtUtil.parse("t")).thenReturn(user);
        when(userDbService.findTokenVersion(7L)).thenReturn(2);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer t");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("ROLE_ADMIN",
                SecurityContextHolder.getContext().getAuthentication().getAuthorities()
                        .iterator().next().getAuthority());
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * 版本不一致：清空上下文（旧令牌失效）。
     *
     * <p>守护 {@code isTokenStillValid} 的版本比对：比错会让改密后旧令牌仍可用。
     */
    @Test
    void versionMismatchShouldClearContext() throws Exception {
        KnowledgeUser user = new KnowledgeUser();
        user.setId(7L);
        user.setRole(UserRole.USER);
        user.setTokenVersion(1);
        when(jwtUtil.parse("t")).thenReturn(user);
        when(userDbService.findTokenVersion(7L)).thenReturn(2);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer t");

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * 用户已被删除（版本查不到）：清空上下文。
     *
     * <p>守护 {@code isTokenStillValid} 的被删分支。
     */
    @Test
    void deletedUserShouldClearContext() throws Exception {
        KnowledgeUser user = new KnowledgeUser();
        user.setId(7L);
        user.setRole(UserRole.USER);
        when(jwtUtil.parse("t")).thenReturn(user);
        when(userDbService.findTokenVersion(7L)).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer t");

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * 令牌里版本为空按 0 处理；库里也是 0 时视为有效。
     *
     * <p>守护 {@code isTokenStillValid} 的空版本兜底。
     */
    @Test
    void nullTokenVersionShouldBeTreatedAsZero() throws Exception {
        KnowledgeUser user = new KnowledgeUser();
        user.setId(7L);
        user.setRole(UserRole.USER);
        when(jwtUtil.parse("t")).thenReturn(user);
        when(userDbService.findTokenVersion(7L)).thenReturn(0);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer t");

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * 令牌解析抛错：清空上下文并继续放行（由后续鉴权拒绝）。
     *
     * <p>守护 {@code doFilterInternal} 的异常兜底：把异常抛出去会变成 500 而不是 40101。
     */
    @Test
    void parseFailureShouldClearContextAndContinue() throws Exception {
        when(jwtUtil.parse("bad")).thenThrow(new IllegalArgumentException("bad token"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * 非 Bearer 头：不解析令牌，直接放行。
     *
     * <p>守护 {@code doFilterInternal} 的头前缀判定。
     */
    @Test
    void nonBearerHeaderShouldBeIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic abc");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(jwtUtil, never()).parse(org.mockito.ArgumentMatchers.any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
