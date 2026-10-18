package com.workbuddy.common.domain.base;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 轻量创建留痕基类：仅 create_by/create_time，无逻辑删除与更新字段。
 */
@Data
public class BaseCreateInfo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 创建人（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private String createBy;

    /** 创建时间（自动填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
