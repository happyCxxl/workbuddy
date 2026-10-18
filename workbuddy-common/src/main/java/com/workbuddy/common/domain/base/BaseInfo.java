package com.workbuddy.common.domain.base;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.workbuddy.common.enums.base.DelFlag;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通用字段基类：逻辑删除标记 + 创建/更新留痕字段。
 *
 * <p>del_flag 按逻辑删除处理；创建/更新字段统一自动填充。
 */
@Data
public class BaseInfo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 删除标记：0 正常 / 1 已删（逻辑删除）；码值见 {@link DelFlag} */
    @TableLogic(value = DelFlag.Code.NORMAL, delval = DelFlag.Code.DELETED)
    @TableField(fill = FieldFill.INSERT)
    private String delFlag;

    /** 创建人（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private String createBy;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新人（自动填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateBy;

    /** 更新时间（自动填充 + DB ON UPDATE） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
