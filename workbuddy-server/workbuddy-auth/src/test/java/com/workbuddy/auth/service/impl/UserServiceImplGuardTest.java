package com.workbuddy.auth.service.impl;

import com.workbuddy.common.domain.entity.WbUser;
import com.workbuddy.common.dto.request.user.UserUpdateDTO;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户维护守卫测试：用户名唯一、角色/状态合法、自保护、最后一个管理员保护。
 *
 * <p>账号生命周期与资料/改密用例分别在 {@link UserServiceImplLifecycleTest}、{@link UserProfileServiceImplTest}；
 * 装配与夹具数据见 {@link UserServiceImplTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplGuardTest {

    @RegisterExtension
    private final UserServiceImplTestFixture fixture = new UserServiceImplTestFixture();

    /**
     * 守护 {@code addUserShouldRejectDuplicateUsername}：该场景行为改动即失败。
     */
    @Test
    void addUserShouldRejectDuplicateUsername() {
        when(fixture.userDbService.findActiveByUsername("admin")).thenReturn(fixture.adminUser(1L));

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> fixture.service().addUser(fixture.createRequest("admin", null, null)));

        assertEquals(ErrorCode.USERNAME_EXISTS, e.getErrorCode());
        verify(fixture.userDbService, never()).save(any());
    }

    /**
     * 守护 {@code addUserShouldRejectInvalidRole}：该场景行为改动即失败。
     */
    @Test
    void addUserShouldRejectInvalidRole() {
        when(fixture.userDbService.findActiveByUsername("newbie")).thenReturn(null);

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> fixture.service().addUser(fixture.createRequest("newbie", "SUPERMAN", null)));

        assertEquals(ErrorCode.ROLE_INVALID, e.getErrorCode());
        verify(fixture.userDbService, never()).save(any());
    }

    /**
     * 守护 {@code updateUserShouldRejectChangingOwnRole}：该场景行为改动即失败。
     */
    @Test
    void updateUserShouldRejectChangingOwnRole() {
        when(fixture.userDbService.getById(1L)).thenReturn(fixture.adminUser(1L));
        fixture.authenticateAs(1L);

        UserUpdateDTO request = new UserUpdateDTO();
        request.setRole(UserRole.USER.getCode());

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> fixture.service().updateUser(1L, request));

        assertEquals(ErrorCode.USER_SELF_OPERATION_FORBIDDEN, e.getErrorCode());
        verify(fixture.userDbService, never()).updateById(any());
    }

    /**
     * 守护 {@code updateUserShouldRejectDisablingOwnAccount}：该场景行为改动即失败。
     */
    @Test
    void updateUserShouldRejectDisablingOwnAccount() {
        when(fixture.userDbService.getById(1L)).thenReturn(fixture.adminUser(1L));
        fixture.authenticateAs(1L);

        UserUpdateDTO request = new UserUpdateDTO();
        request.setStatus(0);

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> fixture.service().updateUser(1L, request));

        assertEquals(ErrorCode.USER_SELF_OPERATION_FORBIDDEN, e.getErrorCode());
    }

    /**
     * 守护 {@code updateUserShouldRejectDemotingLastEnabledAdmin}：该场景行为改动即失败。
     */
    @Test
    void updateUserShouldRejectDemotingLastEnabledAdmin() {
        when(fixture.userDbService.getById(2L)).thenReturn(fixture.adminUser(2L));
        // 操作者是另一个管理员；除目标外已无启用的管理员
        fixture.authenticateAs(99L);

        UserUpdateDTO request = new UserUpdateDTO();
        // 仅降级角色（状态保持启用）：旧实现只看 status，这条路径能绕过守卫
        request.setRole(UserRole.USER.getCode());

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> fixture.service().updateUser(2L, request));

        assertEquals(ErrorCode.LAST_ADMIN_FORBIDDEN, e.getErrorCode());
        verify(fixture.userDbService, never()).updateById(any());
    }

    /**
     * 守护 {@code updateUserShouldAllowDemotingAdminWhenAnotherRemains}：该场景行为改动即失败。
     */
    @Test
    void updateUserShouldAllowDemotingAdminWhenAnotherRemains() {
        when(fixture.userDbService.getById(2L)).thenReturn(fixture.adminUser(2L));
        fixture.authenticateAs(99L);
        when(fixture.userDbService.count(any())).thenReturn(1L);

        UserUpdateDTO request = new UserUpdateDTO();
        request.setRole(UserRole.USER.getCode());

        fixture.service().updateUser(2L, request);

        ArgumentCaptor<WbUser> captor = ArgumentCaptor.forClass(WbUser.class);
        verify(fixture.userDbService).updateById(captor.capture());
        assertEquals(UserRole.USER.getCode(), captor.getValue().getRole());
    }

    /**
     * 守护 {@code updateUserShouldRejectInvalidStatus}：该场景行为改动即失败。
     */
    @Test
    void updateUserShouldRejectInvalidStatus() {
        when(fixture.userDbService.getById(2L)).thenReturn(fixture.plainUser());
        fixture.authenticateAs(99L);

        UserUpdateDTO request = new UserUpdateDTO();
        request.setStatus(99);

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> fixture.service().updateUser(2L, request));

        assertEquals(ErrorCode.USER_STATUS_INVALID, e.getErrorCode());
        verify(fixture.userDbService, never()).updateById(any());
    }

    /**
     * 守护 {@code addUserShouldRejectInvalidStatus}：该场景行为改动即失败。
     */
    @Test
    void addUserShouldRejectInvalidStatus() {
        when(fixture.userDbService.findActiveByUsername("newbie")).thenReturn(null);

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> fixture.service().addUser(fixture.createRequest("newbie", null, 7)));

        assertEquals(ErrorCode.USER_STATUS_INVALID, e.getErrorCode());
        verify(fixture.userDbService, never()).save(any());
    }

    /**
     * 守护 {@code updateUserShouldRejectDisablingLastEnabledAdmin}：该场景行为改动即失败。
     */
    @Test
    void updateUserShouldRejectDisablingLastEnabledAdmin() {
        when(fixture.userDbService.getById(2L)).thenReturn(fixture.adminUser(2L));
        // 操作者是另一个管理员；目标管理员之外已无启用的管理员
        fixture.authenticateAs(99L);

        UserUpdateDTO request = new UserUpdateDTO();
        request.setStatus(0);

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> fixture.service().updateUser(2L, request));

        assertEquals(ErrorCode.LAST_ADMIN_FORBIDDEN, e.getErrorCode());
        verify(fixture.userDbService, never()).updateById(any());
    }

    /**
     * 守护 {@code updateUserShouldAllowDisablingAdminWhenAnotherRemains}：该场景行为改动即失败。
     */
    @Test
    void updateUserShouldAllowDisablingAdminWhenAnotherRemains() {
        when(fixture.userDbService.getById(2L)).thenReturn(fixture.adminUser(2L));
        fixture.authenticateAs(99L);
        when(fixture.userDbService.count(any())).thenReturn(1L);

        UserUpdateDTO request = new UserUpdateDTO();
        request.setStatus(0);

        fixture.service().updateUser(2L, request);

        ArgumentCaptor<WbUser> captor = ArgumentCaptor.forClass(WbUser.class);
        verify(fixture.userDbService).updateById(captor.capture());
        assertEquals(0, captor.getValue().getStatus());
    }

    /**
     * 守护 {@code updateUserShouldThrowWhenUserMissing}：该场景行为改动即失败。
     */
    @Test
    void updateUserShouldThrowWhenUserMissing() {
        when(fixture.userDbService.getById(anyLong())).thenReturn(null);

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> fixture.service().updateUser(404L, new UserUpdateDTO()));

        assertEquals(ErrorCode.USER_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 守护 {@code deleteUserShouldRejectSelfDeletion}：该场景行为改动即失败。
     */
    @Test
    void deleteUserShouldRejectSelfDeletion() {
        when(fixture.userDbService.getById(1L)).thenReturn(fixture.adminUser(1L));
        fixture.authenticateAs(1L);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> fixture.service().deleteUser(1L));

        assertEquals(ErrorCode.USER_SELF_OPERATION_FORBIDDEN, e.getErrorCode());
        verify(fixture.userDbService, never()).removeById(anyLong());
    }

    /**
     * 守护 {@code deleteUserShouldRejectRemovingLastEnabledAdmin}：该场景行为改动即失败。
     */
    @Test
    void deleteUserShouldRejectRemovingLastEnabledAdmin() {
        when(fixture.userDbService.getById(2L)).thenReturn(fixture.adminUser(2L));
        fixture.authenticateAs(99L);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> fixture.service().deleteUser(2L));

        assertEquals(ErrorCode.LAST_ADMIN_FORBIDDEN, e.getErrorCode());
        verify(fixture.userDbService, never()).removeById(anyLong());
    }
}
