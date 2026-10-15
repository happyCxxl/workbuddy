package com.workbuddy.auth.service.impl;

import com.workbuddy.auth.db.UserDbService;
import com.workbuddy.auth.util.JwtUtil;
import com.workbuddy.common.domain.entity.User;
import com.workbuddy.common.dto.request.user.PasswordUpdateRequest;
import com.workbuddy.common.dto.request.user.ProfileUpdateRequest;
import com.workbuddy.common.dto.request.user.UserCreateRequest;
import com.workbuddy.common.dto.request.user.UserUpdateRequest;
import com.workbuddy.common.dto.response.user.UserVO;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.SecurityUtil;
import com.workbuddy.common.security.audit.AuditEvent;
import com.workbuddy.common.security.audit.AuditEventPublisher;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户维护守卫测试：唯一性、角色合法性、自保护、最后一个管理员保护、密码加密。
 *
 * @author cxxl
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplGuardTest {

    @Mock
    private UserDbService userDbService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private UserServiceImpl userService;

    private MockedStatic<SecurityUtil> securityUtils;

    @BeforeEach
    void stubSecurityContext() {
        securityUtils = Mockito.mockStatic(SecurityUtil.class);
        // 默认无认证上下文（匿名）；需要时在用例内覆盖
        securityUtils.when(SecurityUtil::getUser).thenReturn(null);
    }

    @AfterEach
    void releaseSecurityContext() {
        securityUtils.close();
    }

    @Test
    void addUserShouldEncodePasswordAndDefaultRole() {
        when(userDbService.findActiveByUsername("newbie")).thenReturn(null);
        when(passwordEncoder.encode("secret123")).thenReturn("encoded");

        userService.addUser(createRequest("newbie", "secret123", null, null));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userDbService).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("encoded", saved.getPassword());
        assertEquals(UserRole.USER.getCode(), saved.getRole());
        assertEquals(1, saved.getStatus());
    }

    @Test
    void addUserShouldRejectDuplicateUsername() {
        when(userDbService.findActiveByUsername("admin")).thenReturn(adminUser(1L));

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> userService.addUser(createRequest("admin", "secret123", null, null)));

        assertEquals(ErrorCode.USERNAME_EXISTS, e.getErrorCode());
        verify(userDbService, never()).save(any());
    }

    @Test
    void addUserShouldRejectInvalidRole() {
        when(userDbService.findActiveByUsername("newbie")).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> userService.addUser(createRequest("newbie", "secret123", "SUPERMAN", null)));

        assertEquals(ErrorCode.ROLE_INVALID, e.getErrorCode());
        verify(userDbService, never()).save(any());
    }

    @Test
    void updateUserShouldRejectChangingOwnRole() {
        when(userDbService.getById(1L)).thenReturn(adminUser(1L));
        authenticateAs(1L);

        UserUpdateRequest request = new UserUpdateRequest();
        request.setRole(UserRole.USER.getCode());

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> userService.updateUser(1L, request));

        assertEquals(ErrorCode.USER_SELF_OPERATION_FORBIDDEN, e.getErrorCode());
        verify(userDbService, never()).updateById(any());
    }

    @Test
    void updateUserShouldRejectDisablingOwnAccount() {
        when(userDbService.getById(1L)).thenReturn(adminUser(1L));
        authenticateAs(1L);

        UserUpdateRequest request = new UserUpdateRequest();
        request.setStatus(0);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> userService.updateUser(1L, request));

        assertEquals(ErrorCode.USER_SELF_OPERATION_FORBIDDEN, e.getErrorCode());
    }

    @Test
    void updateUserShouldRejectDemotingLastEnabledAdmin() {
        when(userDbService.getById(2L)).thenReturn(adminUser(2L));
        // 操作者是另一个管理员；除目标外已无启用的管理员
        authenticateAs(99L);
        when(userDbService.count(any())).thenReturn(0L);

        UserUpdateRequest request = new UserUpdateRequest();
        // 仅降级角色（状态保持启用）：旧实现只看 status，这条路径能绕过守卫
        request.setRole(UserRole.USER.getCode());

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> userService.updateUser(2L, request));

        assertEquals(ErrorCode.LAST_ADMIN_FORBIDDEN, e.getErrorCode());
        verify(userDbService, never()).updateById(any());
    }

    @Test
    void updateUserShouldAllowDemotingAdminWhenAnotherRemains() {
        when(userDbService.getById(2L)).thenReturn(adminUser(2L));
        authenticateAs(99L);
        when(userDbService.count(any())).thenReturn(1L);

        UserUpdateRequest request = new UserUpdateRequest();
        request.setRole(UserRole.USER.getCode());

        userService.updateUser(2L, request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userDbService).updateById(captor.capture());
        assertEquals(UserRole.USER.getCode(), captor.getValue().getRole());
    }

    @Test
    void updateUserShouldRejectInvalidStatus() {
        when(userDbService.getById(2L)).thenReturn(plainUser(2L));
        authenticateAs(99L);

        UserUpdateRequest request = new UserUpdateRequest();
        request.setStatus(99);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> userService.updateUser(2L, request));

        assertEquals(ErrorCode.USER_STATUS_INVALID, e.getErrorCode());
        verify(userDbService, never()).updateById(any());
    }

    @Test
    void addUserShouldRejectInvalidStatus() {
        when(userDbService.findActiveByUsername("newbie")).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> userService.addUser(createRequest("newbie", "secret123", null, 7)));

        assertEquals(ErrorCode.USER_STATUS_INVALID, e.getErrorCode());
        verify(userDbService, never()).save(any());
    }

    @Test
    void updateUserShouldBumpTokenVersionWhenPasswordChanges() {
        User target = plainUser(2L);
        target.setTokenVersion(4);
        when(userDbService.getById(2L)).thenReturn(target);
        authenticateAs(99L);
        when(passwordEncoder.encode("newpass123")).thenReturn("encoded-new");

        UserUpdateRequest request = new UserUpdateRequest();
        request.setPassword("newpass123");

        userService.updateUser(2L, request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userDbService).updateById(captor.capture());
        // 改密码后旧令牌必须失效
        assertEquals(5, captor.getValue().getTokenVersion());
    }

    @Test
    void updateUserShouldBumpTokenVersionWhenRoleChanges() {
        User target = plainUser(2L);
        target.setTokenVersion(0);
        when(userDbService.getById(2L)).thenReturn(target);
        authenticateAs(99L);

        UserUpdateRequest request = new UserUpdateRequest();
        request.setRole(UserRole.ADMIN.getCode());

        userService.updateUser(2L, request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userDbService).updateById(captor.capture());
        assertEquals(1, captor.getValue().getTokenVersion());
    }

    @Test
    void updateUserShouldKeepTokenVersionWhenNothingSensitiveChanges() {
        User target = plainUser(2L);
        target.setTokenVersion(4);
        when(userDbService.getById(2L)).thenReturn(target);
        authenticateAs(99L);

        // 角色/状态/密码都不变（例如只提交了相同状态）
        UserUpdateRequest request = new UserUpdateRequest();

        userService.updateUser(2L, request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userDbService).updateById(captor.capture());
        assertEquals(4, captor.getValue().getTokenVersion());
    }

    @Test
    void updateUserShouldRejectDisablingLastEnabledAdmin() {
        when(userDbService.getById(2L)).thenReturn(adminUser(2L));
        // 操作者是另一个管理员；目标管理员之外已无启用的管理员
        authenticateAs(99L);
        when(userDbService.count(any())).thenReturn(0L);

        UserUpdateRequest request = new UserUpdateRequest();
        request.setStatus(0);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> userService.updateUser(2L, request));

        assertEquals(ErrorCode.LAST_ADMIN_FORBIDDEN, e.getErrorCode());
        verify(userDbService, never()).updateById(any());
    }

    @Test
    void updateUserShouldAllowDisablingAdminWhenAnotherRemains() {
        when(userDbService.getById(2L)).thenReturn(adminUser(2L));
        authenticateAs(99L);
        when(userDbService.count(any())).thenReturn(1L);

        UserUpdateRequest request = new UserUpdateRequest();
        request.setStatus(0);

        userService.updateUser(2L, request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userDbService).updateById(captor.capture());
        assertEquals(0, captor.getValue().getStatus());
    }

    @Test
    void updateUserShouldEncodePasswordWhenProvided() {
        when(userDbService.getById(2L)).thenReturn(plainUser(2L));
        authenticateAs(99L);
        when(passwordEncoder.encode("newpass123")).thenReturn("encoded-new");

        UserUpdateRequest request = new UserUpdateRequest();
        request.setPassword("newpass123");

        userService.updateUser(2L, request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userDbService).updateById(captor.capture());
        assertEquals("encoded-new", captor.getValue().getPassword());
    }

    @Test
    void updateUserShouldKeepPasswordWhenNotProvided() {
        when(userDbService.getById(2L)).thenReturn(plainUser(2L));
        authenticateAs(99L);

        UserUpdateRequest request = new UserUpdateRequest();
        request.setStatus(0);

        userService.updateUser(2L, request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userDbService).updateById(captor.capture());
        // 未传密码时不得重置为 null 或空
        assertEquals("bcrypt-hash", captor.getValue().getPassword());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void updateUserShouldThrowWhenUserMissing() {
        when(userDbService.getById(anyLong())).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> userService.updateUser(404L, new UserUpdateRequest()));

        assertEquals(ErrorCode.USER_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void deleteUserShouldRejectSelfDeletion() {
        when(userDbService.getById(1L)).thenReturn(adminUser(1L));
        authenticateAs(1L);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> userService.deleteUser(1L));

        assertEquals(ErrorCode.USER_SELF_OPERATION_FORBIDDEN, e.getErrorCode());
        verify(userDbService, never()).removeById(anyLong());
    }

    @Test
    void deleteUserShouldRejectRemovingLastEnabledAdmin() {
        when(userDbService.getById(2L)).thenReturn(adminUser(2L));
        authenticateAs(99L);
        when(userDbService.count(any())).thenReturn(0L);

        KnowledgeException e = assertThrows(KnowledgeException.class, () -> userService.deleteUser(2L));

        assertEquals(ErrorCode.LAST_ADMIN_FORBIDDEN, e.getErrorCode());
        verify(userDbService, never()).removeById(anyLong());
    }

    @Test
    void deleteUserShouldRemovePlainUser() {
        when(userDbService.getById(2L)).thenReturn(plainUser(2L));
        authenticateAs(99L);

        userService.deleteUser(2L);

        verify(userDbService).removeById(eq(2L));
    }

    @Test
    void addUserShouldPublishCreateAuditWithoutPassword() {
        when(userDbService.findActiveByUsername("newbie")).thenReturn(null);
        when(passwordEncoder.encode("secret123")).thenReturn("encoded");

        userService.addUser(createRequest("newbie", "secret123", UserRole.ADMIN.getCode(), null));

        AuditEvent event = captureAudit();
        assertEquals(AuditActionType.USER_CREATE, event.getAction());
        assertEquals("KB_USER", event.getObjectType());
        assertNull(event.getBeforeSummary());
        assertTrue(event.getAfterSummary().contains("newbie"), "摘要应含用户名");
        assertTrue(event.getAfterSummary().contains("ADMIN"), "摘要应含角色");
        assertFalse(event.getAfterSummary().contains("secret123"), "审计摘要不得含明文密码");
        assertFalse(event.getAfterSummary().contains("encoded"), "审计摘要不得含密码哈希");
    }

    @Test
    void updateUserShouldPublishUpdateAuditWithBeforeAndAfter() {
        User target = plainUser(2L);
        target.setTokenVersion(3);
        when(userDbService.getById(2L)).thenReturn(target);
        authenticateAs(99L);

        UserUpdateRequest request = new UserUpdateRequest();
        request.setRole(UserRole.ADMIN.getCode());

        userService.updateUser(2L, request);

        AuditEvent event = captureAudit();
        assertEquals(AuditActionType.USER_UPDATE, event.getAction());
        assertNotNull(event.getBeforeSummary(), "更新审计必须有变更前摘要");
        assertTrue(event.getBeforeSummary().contains("USER"));
        assertTrue(event.getAfterSummary().contains("ADMIN"));
    }

    @Test
    void updateUserShouldPublishDisableAuditWhenOnlyStatusChanges() {
        when(userDbService.getById(2L)).thenReturn(plainUser(2L));
        authenticateAs(99L);

        UserUpdateRequest request = new UserUpdateRequest();
        request.setStatus(0);

        userService.updateUser(2L, request);

        assertEquals(AuditActionType.USER_DISABLE, captureAudit().getAction());
    }

    @Test
    void deleteUserShouldPublishDeleteAudit() {
        when(userDbService.getById(2L)).thenReturn(plainUser(2L));
        authenticateAs(99L);

        userService.deleteUser(2L);

        AuditEvent event = captureAudit();
        assertEquals(AuditActionType.USER_DELETE, event.getAction());
        assertNotNull(event.getBeforeSummary());
        assertNull(event.getAfterSummary());
    }

    @Test
    void addUserShouldCarryProfileFields() {
        when(userDbService.findActiveByUsername("newbie")).thenReturn(null);
        when(passwordEncoder.encode("secret123")).thenReturn("encoded");
        UserCreateRequest request = createRequest("newbie", "secret123", null, null);
        request.setDisplayName("张三");
        request.setEmail("zhangsan@example.com");
        request.setPhone("13800000000");

        userService.addUser(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userDbService).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("张三", saved.getDisplayName());
        assertEquals("zhangsan@example.com", saved.getEmail());
        assertEquals("13800000000", saved.getPhone());
    }

    @Test
    void getUserProfileShouldRejectMissingUser() {
        when(userDbService.getById(9L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> userService.getUserProfile(9L));

        assertEquals(ErrorCode.USER_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void getUserProfileShouldMapProfileFields() {
        User user = plainUser(2L);
        user.setDisplayName("李四");
        user.setEmail("lisi@example.com");
        when(userDbService.getById(2L)).thenReturn(user);

        UserVO vo = userService.getUserProfile(2L);

        assertEquals("李四", vo.getDisplayName());
        assertEquals("lisi@example.com", vo.getEmail());
        assertEquals(UserRole.USER.getCode(), vo.getRole());
    }

    @Test
    void updateProfileShouldUpdateProfileFieldsAndAudit() {
        User user = plainUser(2L);
        user.setDisplayName("旧名");
        when(userDbService.getById(2L)).thenReturn(user);
        ProfileUpdateRequest request = profileRequest("新名", "new@example.com", "13900000000");

        userService.updateProfile(2L, request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userDbService).updateById(captor.capture());
        User updated = captor.getValue();
        assertEquals("新名", updated.getDisplayName());
        assertEquals("new@example.com", updated.getEmail());
        assertEquals("13900000000", updated.getPhone());
        // 资料变更不动角色、状态与令牌版本：不得把人踢下线
        assertEquals(UserRole.USER.getCode(), updated.getRole());
        assertEquals(1, updated.getStatus());
        assertNull(updated.getTokenVersion());

        AuditEvent event = captureAudit();
        assertEquals(AuditActionType.USER_UPDATE, event.getAction());
    }

    @Test
    void updateProfileShouldNormalizeBlankContactToNull() {
        when(userDbService.getById(2L)).thenReturn(plainUser(2L));
        ProfileUpdateRequest request = profileRequest("张三", "   ", "");

        userService.updateProfile(2L, request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userDbService).updateById(captor.capture());
        assertNull(captor.getValue().getEmail());
        assertNull(captor.getValue().getPhone());
    }

    @Test
    void changePasswordShouldRejectWrongOldPassword() {
        when(userDbService.getById(2L)).thenReturn(plainUser(2L));
        when(passwordEncoder.matches("wrong-old", "bcrypt-hash")).thenReturn(false);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> userService.changePassword(2L, passwordRequest("wrong-old", "newpass123")));

        assertEquals(ErrorCode.OLD_PASSWORD_MISMATCH, e.getErrorCode());
        verify(userDbService, never()).updateById(any());
    }

    @Test
    void changePasswordShouldRejectSamePassword() {
        when(userDbService.getById(2L)).thenReturn(plainUser(2L));
        when(passwordEncoder.matches("samepass123", "bcrypt-hash")).thenReturn(true);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> userService.changePassword(2L, passwordRequest("samepass123", "samepass123")));

        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(userDbService, never()).updateById(any());
    }

    @Test
    void changePasswordShouldEncodeBumpVersionAndResign() {
        User user = plainUser(2L);
        user.setTokenVersion(3);
        when(userDbService.getById(2L)).thenReturn(user);
        when(passwordEncoder.matches("oldpass123", "bcrypt-hash")).thenReturn(true);
        when(passwordEncoder.matches("newpass123", "bcrypt-hash")).thenReturn(false);
        when(passwordEncoder.encode("newpass123")).thenReturn("new-hash");
        // 补签令牌沿用本会话剩余时长；测试里无令牌上下文，故落到 1 小时下限
        when(jwtUtil.sign(2L, "user2", UserRole.USER, 4, Duration.ofHours(1))).thenReturn("new-token");

        String token = userService.changePassword(2L, passwordRequest("oldpass123", "newpass123"));

        // 递增令牌版本让旧令牌全部失效，同时用新版本补签一张给当前会话
        assertEquals("new-token", token);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userDbService).updateById(captor.capture());
        assertEquals("new-hash", captor.getValue().getPassword());
        assertEquals(4, captor.getValue().getTokenVersion());

        AuditEvent event = captureAudit();
        assertEquals(AuditActionType.USER_PASSWORD_CHANGE, event.getAction());
        // 审计不落任何密码相关内容
        assertNull(event.getBeforeSummary());
        assertNull(event.getAfterSummary());
    }

    @Test
    void changePasswordShouldRejectMissingUser() {
        when(userDbService.getById(9L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> userService.changePassword(9L, passwordRequest("oldpass123", "newpass123")));

        assertEquals(ErrorCode.USER_NOT_FOUND, e.getErrorCode());
    }

    /** 取发布出去的最后一条审计事件 */
    private AuditEvent captureAudit() {
        ArgumentCaptor<AuditActionType> actionCaptor = ArgumentCaptor.forClass(AuditActionType.class);
        ArgumentCaptor<String> objectTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> objectIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> beforeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> afterCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditEventPublisher).publish(actionCaptor.capture(), objectTypeCaptor.capture(),
                objectIdCaptor.capture(), beforeCaptor.capture(), afterCaptor.capture());
        return new AuditEvent(actionCaptor.getValue(), objectTypeCaptor.getValue(),
                objectIdCaptor.getValue(), beforeCaptor.getValue(), afterCaptor.getValue());
    }

    private PasswordUpdateRequest passwordRequest(String oldPassword, String newPassword) {
        PasswordUpdateRequest request = new PasswordUpdateRequest();
        request.setOldPassword(oldPassword);
        request.setNewPassword(newPassword);
        return request;
    }

    private ProfileUpdateRequest profileRequest(String displayName, String email, String phone) {
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setDisplayName(displayName);
        request.setEmail(email);
        request.setPhone(phone);
        return request;
    }

    private UserCreateRequest createRequest(String username, String password, String role, Integer status) {
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername(username);
        request.setPassword(password);
        request.setRole(role);
        request.setStatus(status);
        return request;
    }

    private User adminUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("admin" + id);
        user.setPassword("bcrypt-hash");
        user.setRole(UserRole.ADMIN.getCode());
        user.setStatus(1);
        return user;
    }

    private User plainUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        user.setPassword("bcrypt-hash");
        user.setRole(UserRole.USER.getCode());
        user.setStatus(1);
        return user;
    }

    /** 指定当前登录用户 ID */
    private void authenticateAs(Long userId) {
        KnowledgeUser current = new KnowledgeUser();
        current.setId(userId);
        current.setUsername("operator");
        current.setRole(UserRole.ADMIN);
        securityUtils.when(SecurityUtil::getUser).thenReturn(current);
        lenient().when(userDbService.count(any())).thenReturn(0L);
    }
}
