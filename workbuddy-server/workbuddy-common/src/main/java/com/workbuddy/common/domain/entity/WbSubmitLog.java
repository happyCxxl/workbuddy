package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workbuddy.common.domain.base.BaseInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 提交日志（wb_submit_log）：每次提交一条（无论成败），
 * 是失败文件的留痕与幂等查询载体。
 *
 * <p>幂等：request_id 唯一约束（uk_request_id），同 requestId 重复提交返回已有记录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wb_submit_log")
public class WbSubmitLog extends BaseInfo {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 目标知识库 */
    private Long knowledgeBaseId;

    /** 幂等键（一次提交唯一） */
    private String requestId;

    /** 文件 ID */
    private String fileId;

    /** 文件指纹（sha256；校验失败不可得时存空串，INSERT 恒写入） */
    @TableField(insertStrategy = FieldStrategy.ALWAYS)
    private String sha256;

    /** 文件名（文件不存在等场景不可得时存空串，同上） */
    @TableField(insertStrategy = FieldStrategy.ALWAYS)
    private String fileName;

    /** 建档回填的文件结果 ID（校验失败为空） */
    private Long fileResultId;

    /** 提交结果：PASS / FAIL */
    private String status;

    /** 失败原因（FileValidationFailReason 枚举名，PASS 时为空） */
    private String failReason;

    /** 提交用户 ID */
    private Long userId;
}
