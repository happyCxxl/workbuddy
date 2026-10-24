package com.workbuddy.auth.service.impl;

import com.workbuddy.auth.db.WbUserDbService;
import com.workbuddy.auth.support.JwtTokenSupport;
import com.workbuddy.common.domain.entity.WbUser;
import com.workbuddy.common.dto.request.user.PasswordUpdateDTO;
import com.workbuddy.common.dto.request.user.ProfileUpdateDTO;
import com.workbuddy.common.dto.request.user.UserCreateDTO;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.security.audit.AuditEvent;
import com.workbuddy.common.security.audit.AuditEventPublisher;
import com.workbuddy.common.utils.SecurityUtil;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 用户维护测试夹具：安全上下文静态 mock 的开启/释放、被测服务装配、账号与请求数据、审计捕获。
 *
 * <p>以 JUnit 扩展注册：{@code @RegisterExtension} 在每个用例前装配、用例后释放静态 mock；
 * 三个用例类共用同一份夹具，不再各自声明依赖。
 */
final class UserServiceImplTestFixture implements BeforeEachCallback, AfterEachCallback {

    WbUserDbService userDbService;
    PasswordEncoder passwordEncoder;
    AuditEventPublisher auditEventPublisher;
    JwtTokenSupport jwtTokenSupport;

    private UserServiceImpl service;

    private MockedStatic<SecurityUtil> securityUtils;

    @Override
    public void beforeEach(ExtensionContext context) {
        userDbService = mock(WbUserDbService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        auditEventPublisher = mock(AuditEventPublisher.class);
        jwtTokenSupport = mock(JwtTokenSupport.class);
        service = new UserServiceImpl(userDbService, passwordEncoder, auditEventPublisher, jwtTokenSupport);
        securityUtils = Mockito.mockStatic(SecurityUtil.class);
        // 默认无认证上下文（匿名）；需要时在用例内覆盖
        securityUtils.when(SecurityUtil::getUser).thenReturn(null);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        securityUtils.close();
    }

    /** 被测服务 */
    UserServiceImpl service() {
        return service;
    }

    /** 取发布出去的最后一条审计事件 */
    AuditEvent captureAudit() {
        ArgumentCaptor<AuditActionType> actionCaptor = ArgumentCaptor.forClass(AuditActionType.class);
        ArgumentCaptor<String> objectTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> objectIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditEventPublisher).publish(actionCaptor.capture(), objectTypeCaptor.capture(),
                objectIdCaptor.capture(), payloadCaptor.capture());
        return new AuditEvent(actionCaptor.getValue(), objectTypeCaptor.getValue(),
                objectIdCaptor.getValue(), payloadCaptor.getValue());
    }

    /** 改密请求夹具 */
    PasswordUpdateDTO passwordRequest(String oldPassword, String newPassword) {
        PasswordUpdateDTO request = new PasswordUpdateDTO();
        request.setOldPassword(oldPassword);
        request.setNewPassword(newPassword);
        return request;
    }

    /** 资料更新请求夹具 */
    ProfileUpdateDTO profileRequest(String displayName, String email, String phone) {
        ProfileUpdateDTO request = new ProfileUpdateDTO();
        request.setDisplayName(displayName);
        request.setEmail(email);
        request.setPhone(phone);
        return request;
    }

    /** 新建用户请求夹具 */
    UserCreateDTO createRequest(String username, String role, Integer status) {
        UserCreateDTO request = new UserCreateDTO();
        request.setUsername(username);
        request.setPassword("secret123");
        request.setRole(role);
        request.setStatus(status);
        return request;
    }

    /** 管理员账号夹具 */
    WbUser adminUser(Long id) {
        WbUser user = new WbUser();
        user.setId(id);
        user.setUsername("admin" + id);
        user.setPassword("bcrypt-hash");
        user.setRole(UserRole.ADMIN.getCode());
        user.setStatus(1);
        return user;
    }

    /** 普通用户账号夹具（id 2、启用） */
    WbUser plainUser() {
        WbUser user = new WbUser();
        user.setId(2L);
        user.setUsername("user" + 2L);
        user.setPassword("bcrypt-hash");
        user.setRole(UserRole.USER.getCode());
        user.setStatus(1);
        return user;
    }

    /** 指定当前登录用户 ID（管理员身份） */
    void authenticateAs(Long userId) {
        KnowledgeUser current = new KnowledgeUser();
        current.setId(userId);
        current.setUsername("operator");
        current.setRole(UserRole.ADMIN);
        securityUtils.when(SecurityUtil::getUser).thenReturn(current);
        lenient().when(userDbService.count(any())).thenReturn(0L);
    }
}
