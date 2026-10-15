package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workbuddy.common.domain.base.BaseInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 文件结果（kb_file_result）：一次提交 = 一行结果，是各环节任务的挂靠锚点。
 * 同一文件重复提交复用 kb_source_file、但新建本行（历史运行产物随之留存）。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("kb_file_result")
public class KbFileResult extends BaseInfo {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属知识库（强制归属） */
    private Long knowledgeBaseId;

    /** 用户归属（检索强制过滤口径，当前统一 ADMIN） */
    private String owner;

    /** 原始文件引用（kb_source_file.id） */
    private Long sourceFileId;

    /** 创建用户 ID */
    private Long userId;

    /** 逻辑删除时间 */
    private LocalDateTime deletedAt;
}
