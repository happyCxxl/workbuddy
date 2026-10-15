package com.workbuddy.common.dto.request.user;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 新增用户请求（管理员操作）。
 *
 * <p>字段与自助注册一致，另可指定角色与状态；两者都不传时缺省为普通用户、启用。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserCreateRequest extends UserCreateBaseDto {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 角色码值：ADMIN / USER（缺省 USER） */
    private String role;

    /** 状态：1 启用 / 0 停用（缺省 1） */
    private Integer status;
}
