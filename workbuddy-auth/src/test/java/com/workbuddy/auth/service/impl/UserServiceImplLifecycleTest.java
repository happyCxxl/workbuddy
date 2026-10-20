package com.workbuddy.auth.service.impl;

import com.workbuddy.common.domain.entity.WbUser;
import com.workbuddy.common.dto.request.user.UserCreateDTO;
import com.workbuddy.common.dto.request.user.UserUpdateDTO;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.security.audit.AuditEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Serializable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户账号生命周期用例：新建默认值与密码加密 / 令牌版本递增规则 / 密码保留 / 删除 / 审计发布。
 *
 * <p>守卫用例与资料/改密用例分别在 {@link UserServiceImplGuardTest}、{@link UserProfileServiceImplTest}；
 * 装配与夹具数据见 {@link UserServiceImplTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplLifecycleTest {

    @RegisterExtension
    private final UserServiceImplTestFixture fixture = new UserServiceImplTestFixture();

    /**
     * 守护 {@code addUserShouldEncodePasswordAndDefaultRole}：该场景行为改动即失败。
     */
    @Test
    void addUserShouldEncodePasswordAndDefaultRole() {
        when(fixture.userDbService.findActiveByUsername("newbie")).thenReturn(null);
        when(fixture.passwordEncoder.encode("secret123")).thenReturn("encoded");

        fixture.service().addUser(fixture.createRequest("newbie", null, null));

        ArgumentCaptor<WbUser> captor = ArgumentCaptor.forClass(WbUser.class);
        verify(fixture.userDbService).save(captor.capture());
        WbUser saved = captor.getValue();
        assertEquals("encoded", saved.getPassword());
        assertEquals(UserRole.USER.getCode(), saved.getRole());
        assertEquals(1, saved.getStatus());
    }

    /**
     * 守护 {@code updateUserShouldBumpTokenVersionWhenPasswordChanges}：该场景行为改动即失败。
     */
    @Test
    void updateUserShouldBumpTokenVersionWhenPasswordChanges() {
        WbUser target = fixture.plainUser();
        target.setTokenVersion(4);
        when(fixture.userDbService.getById(2L)).thenReturn(target);
        fixture.authenticateAs(99L);
        when(fixture.passwordEncoder.encode("newpass123")).thenReturn("encoded-new");

        UserUpdateDTO request = new UserUpdateDTO();
        request.setPassword("newpass123");

        fixture.service().updateUser(2L, request);

        ArgumentCaptor<WbUser> captor = ArgumentCaptor.forClass(WbUser.class);
        verify(fixture.userDbService).updateById(captor.capture());
        // 改密码后旧令牌必须失效
        assertEquals(5, captor.getValue().getTokenVersion());
    }

    /**
     * 守护 {@code updateUserShouldBumpTokenVersionWhenRoleChanges}：该场景行为改动即失败。
     */
    @Test
    void updateUserShouldBumpTokenVersionWhenRoleChanges() {
        WbUser target = fixture.plainUser();
        target.setTokenVersion(0);
        when(fixture.userDbService.getById(2L)).thenReturn(target);
        fixture.authenticateAs(99L);

        UserUpdateDTO request = new UserUpdateDTO();
        request.setRole(UserRole.ADMIN.getCode());

        fixture.service().updateUser(2L, request);

        ArgumentCaptor<WbUser> captor = ArgumentCaptor.forClass(WbUser.class);
        verify(fixture.userDbService).updateById(captor.capture());
        assertEquals(1, captor.getValue().getTokenVersion());
    }

    /**
     * 守护 {@code updateUserShouldKeepTokenVersionWhenNothingSensitiveChanges}：该场景行为改动即失败。
     */
    @Test
    void updateUserShouldKeepTokenVersionWhenNothingSensitiveChanges() {
        WbUser target = fixture.plainUser();
        target.setTokenVersion(4);
        when(fixture.userDbService.getById(2L)).thenReturn(target);
        fixture.authenticateAs(99L);

        // 角色/状态/密码都不变（例如只提交了相同状态）
        UserUpdateDTO request = new UserUpdateDTO();

        fixture.service().updateUser(2L, request);

        ArgumentCaptor<WbUser> captor = ArgumentCaptor.forClass(WbUser.class);
        verify(fixture.userDbService).updateById(captor.capture());
        assertEquals(4, captor.getValue().getTokenVersion());
    }

    /**
     * 守护 {@code deleteUserShouldRemovePlainUser}：该场景行为改动即失败。
     */
    @Test
    void deleteUserShouldRemovePlainUser() {
        when(fixture.userDbService.getById(2L)).thenReturn(fixture.plainUser());
        fixture.authenticateAs(99L);

        fixture.service().deleteUser(2L);

        ArgumentCaptor<Serializable> idCaptor = ArgumentCaptor.forClass(Serializable.class);
        verify(fixture.userDbService).removeById(idCaptor.capture());
        assertEquals(2L, idCaptor.getValue());
    }

    /**
     * 守护 {@code updateUserShouldEncodePasswordWhenProvided}：该场景行为改动即失败。
     */
    @Test
    void updateUserShouldEncodePasswordWhenProvided() {
        when(fixture.userDbService.getById(2L)).thenReturn(fixture.plainUser());
        fixture.authenticateAs(99L);
        when(fixture.passwordEncoder.encode("newpass123")).thenReturn("encoded-new");

        UserUpdateDTO request = new UserUpdateDTO();
        request.setPassword("newpass123");

        fixture.service().updateUser(2L, request);

        ArgumentCaptor<WbUser> captor = ArgumentCaptor.forClass(WbUser.class);
        verify(fixture.userDbService).updateById(captor.capture());
        assertEquals("encoded-new", captor.getValue().getPassword());
    }

    /**
     * 守护 {@code updateUserShouldKeepPasswordWhenNotProvided}：该场景行为改动即失败。
     */
    @Test
    void updateUserShouldKeepPasswordWhenNotProvided() {
        when(fixture.userDbService.getById(2L)).thenReturn(fixture.plainUser());
        fixture.authenticateAs(99L);

        UserUpdateDTO request = new UserUpdateDTO();
        request.setStatus(0);

        fixture.service().updateUser(2L, request);

        ArgumentCaptor<WbUser> captor = ArgumentCaptor.forClass(WbUser.class);
        verify(fixture.userDbService).updateById(captor.capture());
        // 未传密码时不得重置为 null 或空
        assertEquals("bcrypt-hash", captor.getValue().getPassword());
        verify(fixture.passwordEncoder, never()).encode(any());
    }

    /**
     * 守护 {@code addUserShouldPublishCreateAuditWithoutPassword}：该场景行为改动即失败。
     */
    @Test
    void addUserShouldPublishCreateAuditWithoutPassword() {
        when(fixture.userDbService.findActiveByUsername("newbie")).thenReturn(null);
        when(fixture.passwordEncoder.encode("secret123")).thenReturn("encoded");

        fixture.service().addUser(fixture.createRequest("newbie", UserRole.ADMIN.getCode(), null));

        AuditEvent event = fixture.captureAudit();
        assertEquals(AuditActionType.USER_CREATE, event.getAction());
        assertEquals("KB_USER", event.getObjectType());
        assertNull(event.getBeforeSummary());
        assertTrue(event.getAfterSummary().contains("newbie"), "摘要应含用户名");
        assertTrue(event.getAfterSummary().contains("ADMIN"), "摘要应含角色");
        assertFalse(event.getAfterSummary().contains("secret123"), "审计摘要不得含明文密码");
        assertFalse(event.getAfterSummary().contains("encoded"), "审计摘要不得含密码哈希");
    }

    /**
     * 守护 {@code updateUserShouldPublishUpdateAuditWithBeforeAndAfter}：该场景行为改动即失败。
     */
    @Test
    void updateUserShouldPublishUpdateAuditWithBeforeAndAfter() {
        WbUser target = fixture.plainUser();
        target.setTokenVersion(3);
        when(fixture.userDbService.getById(2L)).thenReturn(target);
        fixture.authenticateAs(99L);

        UserUpdateDTO request = new UserUpdateDTO();
        request.setRole(UserRole.ADMIN.getCode());

        fixture.service().updateUser(2L, request);

        AuditEvent event = fixture.captureAudit();
        assertEquals(AuditActionType.USER_UPDATE, event.getAction());
        assertNotNull(event.getBeforeSummary(), "更新审计必须有变更前摘要");
        assertTrue(event.getBeforeSummary().contains("USER"));
        assertTrue(event.getAfterSummary().contains("ADMIN"));
    }

    /**
     * 守护 {@code updateUserShouldPublishDisableAuditWhenOnlyStatusChanges}：该场景行为改动即失败。
     */
    @Test
    void updateUserShouldPublishDisableAuditWhenOnlyStatusChanges() {
        when(fixture.userDbService.getById(2L)).thenReturn(fixture.plainUser());
        fixture.authenticateAs(99L);

        UserUpdateDTO request = new UserUpdateDTO();
        request.setStatus(0);

        fixture.service().updateUser(2L, request);

        assertEquals(AuditActionType.USER_DISABLE, fixture.captureAudit().getAction());
    }

    /**
     * 守护 {@code deleteUserShouldPublishDeleteAudit}：该场景行为改动即失败。
     */
    @Test
    void deleteUserShouldPublishDeleteAudit() {
        when(fixture.userDbService.getById(2L)).thenReturn(fixture.plainUser());
        fixture.authenticateAs(99L);

        fixture.service().deleteUser(2L);

        AuditEvent event = fixture.captureAudit();
        assertEquals(AuditActionType.USER_DELETE, event.getAction());
        assertNotNull(event.getBeforeSummary());
        assertNull(event.getAfterSummary());
    }

    /**
     * 守护 {@code addUserShouldCarryProfileFields}：该场景行为改动即失败。
     */
    @Test
    void addUserShouldCarryProfileFields() {
        when(fixture.userDbService.findActiveByUsername("newbie")).thenReturn(null);
        when(fixture.passwordEncoder.encode("secret123")).thenReturn("encoded");
        UserCreateDTO request = fixture.createRequest("newbie", null, null);
        request.setDisplayName("张三");
        request.setEmail("zhangsan@example.com");
        request.setPhone("13800000000");

        fixture.service().addUser(request);

        ArgumentCaptor<WbUser> captor = ArgumentCaptor.forClass(WbUser.class);
        verify(fixture.userDbService).save(captor.capture());
        WbUser saved = captor.getValue();
        assertEquals("张三", saved.getDisplayName());
        assertEquals("zhangsan@example.com", saved.getEmail());
        assertEquals("13800000000", saved.getPhone());
    }
}
