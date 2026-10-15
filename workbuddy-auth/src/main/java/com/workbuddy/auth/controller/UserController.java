package com.workbuddy.auth.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.auth.service.UserService;
import com.workbuddy.common.dto.response.R;
import com.workbuddy.common.dto.request.user.PasswordUpdateRequest;
import com.workbuddy.common.dto.request.user.ProfileUpdateRequest;
import com.workbuddy.common.dto.request.user.UserCreateRequest;
import com.workbuddy.common.dto.request.user.UserUpdateRequest;
import com.workbuddy.common.dto.response.user.UserVO;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.annotation.AdminOnly;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.utils.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口：平台账号的查询与维护。
 *
 * <p>管理类接口逐个标注 {@link AdminOnly}；个人信息接口对本人开放。
 * **新增管理类方法必须一并标注 {@link AdminOnly}**，类级注解已移除，漏标即对普通用户开放。
 *
 * @author cxxl
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/profile")
    @Operation(summary = "查询个人信息", description = "当前登录账号的真实姓名、邮箱与手机号；响应不含密码")
    public R<UserVO> getUserProfile() {
        return R.ok(userService.getUserProfile(currentUserId()));
    }

    @PutMapping("/profile")
    @Operation(summary = "修改个人信息", description = "本人修改真实姓名、邮箱、手机号")
    public R<Void> updateProfile(@Valid @RequestBody ProfileUpdateRequest request) {
        userService.updateProfile(currentUserId(), request);
        return R.ok(null, "更新成功");
    }

    @PutMapping("/password")
    @Operation(summary = "修改密码", description = "本人修改密码：须提供当前密码，新密码 8-10 位且不得与当前密码相同；"
            + "修改后该账号此前签发的令牌全部失效，响应返回补签的新令牌")
    public R<String> changePassword(@Valid @RequestBody PasswordUpdateRequest request) {
        return R.ok(userService.changePassword(currentUserId(), request), "密码已修改");
    }

    @AdminOnly
    @GetMapping("/page")
    @Operation(summary = "分页查询用户", description = "分页列表，支持用户名模糊查询与角色/状态过滤")
    public R<IPage<UserVO>> pageUsers(
            @Parameter(description = "当前页") @RequestParam(value = "current", defaultValue = "1") long current,
            @Parameter(description = "每页条数") @RequestParam(value = "size", defaultValue = "10") long size,
            @Parameter(description = "用户名") @RequestParam(value = "username", required = false) String username,
            @Parameter(description = "角色码值") @RequestParam(value = "role", required = false) String role,
            @Parameter(description = "状态值") @RequestParam(value = "status", required = false) Integer status) {
        return R.ok(userService.pageUsers(current, size, username, role, status));
    }

    @AdminOnly
    @PostMapping("/add")
    @Operation(summary = "新增用户", description = "管理员新增账号；用户名全局唯一，密码 BCrypt 加密落库")
    public R<String> addUser(@Valid @RequestBody UserCreateRequest request) {
        return R.ok(String.valueOf(userService.addUser(request)), "新增成功");
    }

    @AdminOnly
    @PutMapping("/{id}")
    @Operation(summary = "编辑用户", description = "管理员编辑账号：用户名不可改，密码不传表示不重置，"
            + "角色/状态不传表示不变；不允许改自身角色或状态")
    public R<Void> updateUser(
            @Parameter(description = "用户ID", required = true) @PathVariable("id") Long id,
            @Valid @RequestBody UserUpdateRequest request) {
        userService.updateUser(id, request);
        return R.ok(null, "更新成功");
    }

    @AdminOnly
    @DeleteMapping("/{id}")
    @Operation(summary = "删除用户", description = "逻辑删除；不允许删除自身；不允许删除最后一个启用的管理员")
    public R<Void> deleteUser(
            @Parameter(description = "用户ID", required = true) @PathVariable("id") Long id) {
        userService.deleteUser(id);
        return R.ok(null, "删除成功");
    }

    /**
     * 当前登录用户主键；无认证上下文视为未认证
     */
    private Long currentUserId() {
        KnowledgeUser user = SecurityUtil.getUser();
        ThrowUtil.throwIf(NullUtil.isNull(user) || NullUtil.isNull(user.getId()), ErrorCode.UNAUTHORIZED);
        return user.getId();
    }
}
