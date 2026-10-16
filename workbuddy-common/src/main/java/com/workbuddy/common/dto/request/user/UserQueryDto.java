package com.workbuddy.common.dto.request.user;

import com.workbuddy.common.dto.request.page.PageQueryDto;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 用户清单的分页查询条件（管理员接口收参）：用户名 / 角色 / 状态三项都可留空。
 *
 * <p>页码与页长口径见 {@link PageQueryDto}。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQueryDto extends PageQueryDto {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户名模糊关键字（可空） */
    private String username;

    /** 角色（可空，字典名：ADMIN / USER） */
    private String role;

    /** 状态（可空，1 启用 / 0 停用） */
    private Integer status;
}
