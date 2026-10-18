package com.workbuddy.biz.controller.auth;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.auth.service.UserService;
import com.workbuddy.common.web.R;
import com.workbuddy.common.dto.request.user.PasswordUpdateDTO;
import com.workbuddy.common.dto.request.user.ProfileUpdateDTO;
import com.workbuddy.common.dto.request.user.UserCreateDTO;
import com.workbuddy.common.dto.request.user.UserQueryDTO;
import com.workbuddy.common.dto.request.user.UserUpdateDTO;
import com.workbuddy.common.dto.response.user.UserVO;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.annotation.AdminOnly;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.utils.SecurityUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
    public R<UserVO> getUserProfile() {
        return R.ok(userService.getUserProfile(currentUserId()));
    }

    @PutMapping("/profile")
    public R<Void> updateProfile(@Valid @RequestBody ProfileUpdateDTO request) {
        userService.updateProfile(currentUserId(), request);
        return R.ok(null, "更新成功");
    }

    @PutMapping("/password")
    public R<String> changePassword(@Valid @RequestBody PasswordUpdateDTO request) {
        return R.ok(userService.changePassword(currentUserId(), request), "密码已修改");
    }

    @AdminOnly
    @GetMapping("/page")
    public R<IPage<UserVO>> pageUsers(@Valid UserQueryDTO query) {
        return R.ok(userService.pageUsers(query));
    }

    @AdminOnly
    @PostMapping("/add")
    public R<String> addUser(@Valid @RequestBody UserCreateDTO request) {
        return R.ok(String.valueOf(userService.addUser(request)), "新增成功");
    }

    @AdminOnly
    @PutMapping("/{id}")
    public R<Void> updateUser(
            @PathVariable("id") Long id,
            @Valid @RequestBody UserUpdateDTO request) {
        userService.updateUser(id, request);
        return R.ok(null, "更新成功");
    }

    @AdminOnly
    @DeleteMapping("/{id}")
    public R<Void> deleteUser(
            @PathVariable("id") Long id) {
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
