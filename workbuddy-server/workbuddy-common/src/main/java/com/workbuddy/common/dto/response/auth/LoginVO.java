package com.workbuddy.common.dto.response.auth;

import lombok.Data;

/**
 * 登录结果。
 *
 * <p>只带令牌里没有、界面又需要的用户信息：角色与用户 ID 可从令牌载荷解析，不重复下发。
 */
@Data
public class LoginVO {

    /**
     * 主键（雪花）
     */
    private Long id;

    /**
     * 登录用户名
     */
    private String username;

    /**
     * 真实姓名
     */
    private String displayName;

    /**
     * 邮箱
     */
    private String email;

    /**
     * 手机号
     */
    private String phone;


    /**
     * 状态：1 启用 / 0 停用
     */
    private Integer status;

    /**
     * 角色码值：ADMIN 管理员 / USER 普通用户（见 UserRole）
     */
    private String role;

    /**
     * 令牌版本：递增即让该账号已签发的令牌全部失效（改密码/改角色/停用/删除时 +1）
     */
    private Integer tokenVersion;

    /**
     * 头像地址（/user/avatar/{id}?v={版本段}）；未设置头像时为 null，界面回落姓名首字
     */
    private String avatar;

    /**
     * 访问令牌
     */
    private String token;
}
