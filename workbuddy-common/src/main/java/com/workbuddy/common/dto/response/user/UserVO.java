package com.workbuddy.common.dto.response.user;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户视图对象（status 返回码值 1/0；不含密码字段）。
 */
@Data
public class UserVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花 ID，前端按字符串处理防精度丢失） */
    private Long id;

    /** 登录用户名 */
    private String username;

    /** 真实姓名 */
    private String displayName;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 头像地址（/user/avatar/{id}?v={版本段}）；未设置头像时为 null */
    private String avatar;

    /** 状态码值：1 启用 / 0 停用 */
    private Integer status;

    /** 角色码值：ADMIN 管理员 / USER 普通用户 */
    private String role;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}
