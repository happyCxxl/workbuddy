package com.workbuddy.auth.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.common.dto.request.user.PasswordUpdateRequest;
import com.workbuddy.common.dto.request.user.ProfileUpdateRequest;
import com.workbuddy.common.dto.request.user.UserCreateRequest;
import com.workbuddy.common.dto.request.user.UserQueryDto;
import com.workbuddy.common.dto.request.user.UserUpdateRequest;
import com.workbuddy.common.dto.response.user.UserVO;

/**
 * 用户服务：用户账号的查询与维护。
 *
 * <p>维护类操作（新增/编辑/删除）由管理员接口调用，服务层负责唯一性、自保护与
 * 「最后一个管理员」守卫。
 *
 * @author cxxl
 */
public interface UserService {

    /**
     * 分页查询用户（不含已删除；响应不含密码），支持用户名模糊、角色与状态过滤。
     *
     * @param query 查询条件（页码与页长口径见 {@link UserQueryDto} 父类）
     * @return 用户分页结果
     */
    IPage<UserVO> pageUsers(UserQueryDto query);

    /**
     * 查询个人信息（本人操作，响应不含密码）。
     *
     * @param id 用户主键
     * @return 用户资料
     */
    UserVO getUserProfile(Long id);

    /**
     * 修改个人信息（本人操作）：只改真实姓名、邮箱、手机号，不动角色、状态与令牌版本。
     *
     * @param id      用户主键
     * @param request 个人信息请求
     */
    void updateProfile(Long id, ProfileUpdateRequest request);

    /**
     * 修改密码（本人操作）：校验当前密码后写入新密码，并递增令牌版本让该账号此前签发的令牌全部失效。
     *
     * @param id      用户主键
     * @param request 修改密码请求
     * @return 以新令牌版本补签的令牌（当前会话可继续使用，不必重新登录）
     */
    String changePassword(Long id, PasswordUpdateRequest request);

    /**
     * 新增用户（管理员操作）。
     *
     * @param request 新增请求（用户名唯一、密码 BCrypt 加密）
     * @return 新用户主键
     */
    Long addUser(UserCreateRequest request);

    /**
     * 编辑用户（管理员操作）：用户名不可改，密码不传表示不重置。
     *
     * @param id      用户主键
     * @param request 编辑请求
     */
    void updateUser(Long id, UserUpdateRequest request);

    /**
     * 删除用户（逻辑删除，管理员操作）。
     *
     * @param id 用户主键
     */
    void deleteUser(Long id);
}
