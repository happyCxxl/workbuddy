package com.workbuddy.biz.controller.auth;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.auth.service.UserService;
import com.workbuddy.common.dto.request.user.PasswordUpdateDTO;
import com.workbuddy.common.dto.request.user.ProfileUpdateDTO;
import com.workbuddy.common.dto.request.user.UserCreateDTO;
import com.workbuddy.common.dto.request.user.UserQueryDTO;
import com.workbuddy.common.dto.request.user.UserUpdateDTO;
import com.workbuddy.common.dto.response.user.UserVO;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.SecurityUtil;
import com.workbuddy.common.web.R;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户接口单测：当前用户主键取自安全上下文、管理接口的路径 ID 透传、未认证的拒绝口径。
 */
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    private static final Long CURRENT_USER_ID = 7L;

    @Mock
    private UserService userService;

    private MockedStatic<SecurityUtil> security;

    private UserController controller;

    /**
     * 每个用例重建控制器并注入当前登录用户。
     */
    @BeforeEach
    void setUp() {
        security = mockStatic(SecurityUtil.class);
        KnowledgeUser user = new KnowledgeUser();
        user.setId(CURRENT_USER_ID);
        security.when(SecurityUtil::getUser).thenReturn(user);
        controller = new UserController(userService);
    }

    /**
     * 收尾关闭静态桩。
     */
    @AfterEach
    void tearDown() {
        security.close();
    }

    /**
     * 档案查询与修改都按当前登录用户 ID 定位。
     *
     * <p>守护 {@code currentUserId} 的取值来源：从请求参数取 ID 会越权读别人的档案。
     */
    @Test
    void profileEndpointsShouldUseCurrentUserId() {
        UserVO vo = new UserVO();
        ProfileUpdateDTO request = new ProfileUpdateDTO();
        when(userService.getUserProfile(CURRENT_USER_ID)).thenReturn(vo);

        assertSame(vo, controller.getUserProfile().getData());
        controller.updateProfile(request);

        verify(userService).updateProfile(CURRENT_USER_ID, request);
    }

    /**
     * 改密返回重签的令牌并带成功提示。
     *
     * <p>守护 {@code changePassword} 的响应载荷：令牌丢失会让前端继续用已失效的旧令牌。
     */
    @Test
    void changePasswordShouldReturnResignedToken() {
        PasswordUpdateDTO request = new PasswordUpdateDTO();
        when(userService.changePassword(CURRENT_USER_ID, request)).thenReturn("new-token");

        R<String> response = controller.changePassword(request);

        assertEquals("new-token", response.getData());
        assertEquals(R.SUCCESS, response.getCode());
        assertEquals("密码已修改", response.getMsg());
    }

    /**
     * 分页查询原样透传查询条件。
     *
     * <p>守护 {@code pageUsers} 的参数透传。
     */
    @Test
    void pageUsersShouldDelegateQuery() {
        UserQueryDTO query = new UserQueryDTO();
        IPage<UserVO> page = new Page<>(1, 10);
        when(userService.pageUsers(query)).thenReturn(page);

        assertSame(page, controller.pageUsers(query).getData());
    }

    /**
     * 新增用户把服务返回的主键转成字符串。
     *
     * <p>守护 {@code addUser} 的主键类型：前端按字符串接收，返回数字会解析失败。
     */
    @Test
    void addUserShouldReturnStringId() {
        UserCreateDTO request = new UserCreateDTO();
        when(userService.addUser(request)).thenReturn(12L);

        assertEquals("12", controller.addUser(request).getData());
    }

    /**
     * 管理端修改与删除按路径 ID 定位。
     *
     * <p>守护 {@code updateUser}/{@code deleteUser} 的 ID 透传：误用当前用户 ID 会改到自己头上。
     */
    @Test
    void adminEndpointsShouldUsePathId() {
        UserUpdateDTO request = new UserUpdateDTO();

        assertEquals(R.SUCCESS, controller.updateUser(3L, request).getCode());
        assertEquals(R.SUCCESS, controller.deleteUser(4L).getCode());

        verify(userService).updateUser(3L, request);
        verify(userService).deleteUser(4L);
        verify(userService, Mockito.never()).deleteUser(CURRENT_USER_ID);
    }

    /**
     * 无认证上下文或用户无主键一律按未认证拒绝。
     *
     * <p>守护 {@code currentUserId} 的两段判定：放行会以 null 主键查库。
     */
    @Test
    void currentUserIdShouldRejectMissingLogin() {
        security.when(SecurityUtil::getUser).thenReturn(null);
        WorkbuddyException noLogin = assertThrows(WorkbuddyException.class, controller::getUserProfile);
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), noLogin.getCode());

        KnowledgeUser anonymous = new KnowledgeUser();
        security.when(SecurityUtil::getUser).thenReturn(anonymous);
        WorkbuddyException noId = assertThrows(WorkbuddyException.class, controller::getUserProfile);
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), noId.getCode());
    }
}
