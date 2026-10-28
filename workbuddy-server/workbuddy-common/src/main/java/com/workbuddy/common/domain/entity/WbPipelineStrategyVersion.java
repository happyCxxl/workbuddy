package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 环节策略版本：预处理/切片/向量化/检索策略的配置行。
 * 口径（策略版本行不可变）：行一旦注册不可改——编辑 = 复制新版本行
 * （uk_type_name_version 唯一键兜底），停用代替删除（有绑定引用禁物理删除），启停保留。
 * 对齐 DDL 原设计（表注释"不可变，修改即新版本"）；历史任务自持策略快照，不受影响。
 */
@Data
@TableName("wb_knowledge_pipeline_strategy_version")
public class WbPipelineStrategyVersion {

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 策略类型：PREPROCESS/CHUNK/EMBED */
    private String type;

    /** 策略名（如 preproc-default） */
    private String name;

    /** 版本号（如 v1） */
    private String version;

    /** 配置快照（JSON 文本，完整参数：规则开关） */
    private String configSnapshot;

    /** 状态：ACTIVE/INACTIVE */
    private String status;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;
}
