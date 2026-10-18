package com.workbuddy.auth.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.auth.db.WbUserDbService;
import com.workbuddy.common.domain.entity.WbUser;
import com.workbuddy.common.dto.request.user.UserQueryDTO;
import com.workbuddy.common.dto.response.user.UserVO;
import com.workbuddy.common.enums.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 用户服务测试：分页映射与敏感字段隔离。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private WbUserDbService userDbService;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void pageUsersShouldMapEntityToVo() {
        Page<WbUser> source = new Page<>(1, 10, 2);
        source.setRecords(List.of(user(1L, "admin", UserRole.ADMIN), user(2L, "tom", UserRole.USER)));
        when(userDbService.pageByCondition(eq(1L), eq(10L), any(), any(), any())).thenReturn(source);

        IPage<UserVO> result = userService.pageUsers(userQuery(1L, 10L, null, null, null));

        assertEquals(2, result.getTotal());
        assertEquals(1, result.getCurrent());
        assertEquals(10, result.getSize());
        UserVO first = result.getRecords().get(0);
        assertEquals(1L, first.getId());
        assertEquals("admin", first.getUsername());
        assertEquals(UserRole.ADMIN.getCode(), first.getRole());
        assertEquals(UserRole.USER.getCode(), result.getRecords().get(1).getRole());
    }

    @Test
    void userVoShouldNotExposePasswordField() {
        // 密码泄漏是编译期就该拦住的：UserVO 不允许出现 password 属性
        List<String> fields = Arrays.stream(UserVO.class.getDeclaredFields()).map(Field::getName).toList();

        assertFalse(fields.contains("password"), "UserVO 不得包含 password 字段");
        assertTrue(fields.contains("username"), "UserVO 应包含 username 字段");
        assertTrue(fields.contains("role"), "UserVO 应包含 role 字段");
    }

    @Test
    void pageUsersShouldPassFiltersThrough() {
        Page<WbUser> source = new Page<>(2, 5, 0);
        source.setRecords(List.of());
        when(userDbService.pageByCondition(eq(2L), eq(5L), eq("tom"), eq("ADMIN"), eq(0))).thenReturn(source);

        IPage<UserVO> result = userService.pageUsers(userQuery(2L, 5L, "tom", "ADMIN", 0));

        assertEquals(0, result.getRecords().size());
        assertEquals(2, result.getCurrent());
    }

    /** 分页查询条件（页码与页长可空 = 缺省口径） */
    private UserQueryDTO userQuery(Long current, Long size, String username, String role, Integer status) {
        UserQueryDTO query = new UserQueryDTO();
        query.setCurrent(current);
        query.setSize(size);
        query.setUsername(username);
        query.setRole(role);
        query.setStatus(status);
        return query;
    }

    private WbUser user(Long id, String username, UserRole role) {
        WbUser user = new WbUser();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("bcrypt-hash-should-never-leak");
        user.setStatus(1);
        user.setRole(role.getCode());
        return user;
    }
}
