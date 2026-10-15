package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workbuddy.common.domain.task.StepLogFields;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 子步骤记录：环节内每个子步骤一条（路由/原生解析/质量检查…），审计与排查依据。
 * 机器表：后台线程写入、append-only、不逻辑删——不挂平台五件套（仅 create_time）。
 *
 * @author cxxl
 */
@Data
@TableName("wb_knowledge_pipeline_step_log")
public class WbPipelineStepLog implements StepLogFields {

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属处理链任务（wb_knowledge_pipeline_task.id） */
    private Long taskId;

    /** 子步骤名（文件级路由/原生解析/质量检查…） */
    private String stepName;

    /** 步骤状态（StepStatus 枚举名） */
    private String status;

    /** 能力/模型版本（如 pdfbox-3.0.4） */
    private String capabilityVersion;

    /** 输入产物引用（可空） */
    private Long inputRef;

    /** 输出产物引用（可空） */
    private Long outputRef;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;

    /** 耗时（毫秒） */
    private Integer duration;

    /** 告警数 */
    private Integer warningCount;

    /** 命中数（规则命中次数；解析环节为 0） */
    private Integer matchedCount;

    /** 变更数（实际改写次数；解析环节为 0） */
    private Integer changedCount;

    /** 平均长度（切片子步骤统计；其他环节为 0） */
    private Integer avgLen;

    /** 错误信息 */
    private String error;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;
}
