package com.workbuddy.auth.service.impl;

import com.workbuddy.common.domain.entity.WbUser;
import com.workbuddy.common.dto.response.user.UserVO;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.security.audit.AuditEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户资料与改密用例：资料读取与更新（空联系方式归一）/ 改密（旧密码、同密码、令牌补签、审计）。
 *
 * <p>守卫与账号生命周期用例分别在 {@link UserServiceImplGuardTest}、{@link UserServiceImplLifecycleTest}；
 * 装配与夹具数据见 {@link UserServiceImplTestFixture}。
 */
@ExtendWith(MockitoExtension.class)
class UserProfileServiceImplTest {

    @RegisterExtension
    private final UserServiceImplTestFixture fixture = new UserServiceImplTestFixture();

    /**
     * 守护 {@code getUserProfileShouldRejectMissingUser}：该场景行为改动即失败。
     */
    @Test
    void getUserProfileShouldRejectMissingUser() {
        when(fixture.userDbService.getById(9L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> fixture.service().getUserProfile(9L));

        assertEquals(ErrorCode.USER_NOT_FOUND, e.getErrorCode());
    }

    /**
     * 守护 {@code getUserProfileShouldMapProfileFields}：该场景行为改动即失败。
     */
    @Test
    void getUserProfileShouldMapProfileFields() {
        WbUser user = fixture.plainUser();
        user.setDisplayName("李四");
        user.setEmail("lisi@example.com");
        when(fixture.userDbService.getById(2L)).thenReturn(user);

        UserVO vo = fixture.service().getUserProfile(2L);

        assertEquals("李四", vo.getDisplayName());
        assertEquals("lisi@example.com", vo.getEmail());
        assertEquals(UserRole.USER.getCode(), vo.getRole());
    }

    /**
     * 守护 {@code updateProfileShouldUpdateProfileFieldsAndAudit}：该场景行为改动即失败。
     */
    @Test
    void updateProfileShouldUpdateProfileFieldsAndAudit() {
        WbUser user = fixture.plainUser();
        user.setDisplayName("旧名");
        when(fixture.userDbService.getById(2L)).thenReturn(user);
        var request = fixture.profileRequest("新名", "new@example.com", "13900000000");

        fixture.service().updateProfile(2L, request);

        ArgumentCaptor<WbUser> captor = ArgumentCaptor.forClass(WbUser.class);
        verify(fixture.userDbService).updateById(captor.capture());
        WbUser updated = captor.getValue();
        assertEquals("新名", updated.getDisplayName());
        assertEquals("new@example.com", updated.getEmail());
        assertEquals("13900000000", updated.getPhone());
        // 资料变更不动角色、状态与令牌版本：不得把人踢下线
        assertEquals(UserRole.USER.getCode(), updated.getRole());
        assertEquals(1, updated.getStatus());
        assertNull(updated.getTokenVersion());

        AuditEvent event = fixture.captureAudit();
        assertEquals(AuditActionType.USER_UPDATE, event.getAction());
    }

    /**
     * 守护 {@code updateProfileShouldNormalizeBlankContactToNull}：该场景行为改动即失败。
     */
    @Test
    void updateProfileShouldNormalizeBlankContactToNull() {
        when(fixture.userDbService.getById(2L)).thenReturn(fixture.plainUser());
        var request = fixture.profileRequest("张三", "   ", "");

        fixture.service().updateProfile(2L, request);

        ArgumentCaptor<WbUser> captor = ArgumentCaptor.forClass(WbUser.class);
        verify(fixture.userDbService).updateById(captor.capture());
        assertNull(captor.getValue().getEmail());
        assertNull(captor.getValue().getPhone());
    }

    /**
     * 守护 {@code changePasswordShouldRejectWrongOldPassword}：该场景行为改动即失败。
     */
    @Test
    void changePasswordShouldRejectWrongOldPassword() {
        when(fixture.userDbService.getById(2L)).thenReturn(fixture.plainUser());
        when(fixture.passwordEncoder.matches("wrong-old", "bcrypt-hash")).thenReturn(false);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> fixture.service().changePassword(2L, fixture.passwordRequest("wrong-old", "newpass123")));

        assertEquals(ErrorCode.OLD_PASSWORD_MISMATCH, e.getErrorCode());
        verify(fixture.userDbService, never()).updateById(any());
    }

    /**
     * 守护 {@code changePasswordShouldRejectSamePassword}：该场景行为改动即失败。
     */
    @Test
    void changePasswordShouldRejectSamePassword() {
        when(fixture.userDbService.getById(2L)).thenReturn(fixture.plainUser());
        when(fixture.passwordEncoder.matches("samepass123", "bcrypt-hash")).thenReturn(true);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> fixture.service().changePassword(2L,
                        fixture.passwordRequest("samepass123", "samepass123")));

        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(fixture.userDbService, never()).updateById(any());
    }

    /**
     * 守护 {@code changePasswordShouldEncodeBumpVersionAndResign}：该场景行为改动即失败。
     */
    @Test
    void changePasswordShouldEncodeBumpVersionAndResign() {
        WbUser user = fixture.plainUser();
        user.setTokenVersion(3);
        when(fixture.userDbService.getById(2L)).thenReturn(user);
        when(fixture.passwordEncoder.matches("oldpass123", "bcrypt-hash")).thenReturn(true);
        when(fixture.passwordEncoder.matches("newpass123", "bcrypt-hash")).thenReturn(false);
        when(fixture.passwordEncoder.encode("newpass123")).thenReturn("new-hash");
        // 补签令牌沿用本会话剩余时长；测试里无令牌上下文，故落到 1 小时下限
        when(fixture.jwtTokenSupport.sign(2L, "user2", UserRole.USER, 4, Duration.ofHours(1)))
                .thenReturn("new-token");

        String token = fixture.service().changePassword(2L,
                fixture.passwordRequest("oldpass123", "newpass123"));

        // 递增令牌版本让旧令牌全部失效，同时用新版本补签一张给当前会话
        assertEquals("new-token", token);
        ArgumentCaptor<WbUser> captor = ArgumentCaptor.forClass(WbUser.class);
        verify(fixture.userDbService).updateById(captor.capture());
        assertEquals("new-hash", captor.getValue().getPassword());
        assertEquals(4, captor.getValue().getTokenVersion());

        AuditEvent event = fixture.captureAudit();
        assertEquals(AuditActionType.USER_PASSWORD_CHANGE, event.getAction());
        // 审计不落任何密码相关内容
        assertNull(event.getBeforeSummary());
        assertNull(event.getAfterSummary());
    }

    /**
     * 守护 {@code changePasswordShouldRejectMissingUser}：该场景行为改动即失败。
     */
    @Test
    void changePasswordShouldRejectMissingUser() {
        when(fixture.userDbService.getById(9L)).thenReturn(null);

        KnowledgeException e = assertThrows(KnowledgeException.class,
                () -> fixture.service().changePassword(9L,
                        fixture.passwordRequest("oldpass123", "newpass123")));

        assertEquals(ErrorCode.USER_NOT_FOUND, e.getErrorCode());
    }
}
