package com.workbuddy.common.dto.request.user;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 个人信息修改请求（本人操作）。
 *
 * <p>只能改真实姓名、邮箱、手机号；用户名、角色、状态与密码都不在此接口范围内。
 * 三个字段与其校验口径来自父类 {@link UserContactBaseDTO} —— 与注册 / 新增账号是同一套，
 * 不在这里重写一遍（改了父类三处同步）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProfileUpdateDTO extends UserContactBaseDTO {

    @Serial
    private static final long serialVersionUID = 1L;
}
