package com.workbuddy.common.dto.response.strategy;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 策略版本 VO：前端策略选择下拉与管理列表数据源。
 */
@Data
public class StrategyVersionVO {

    /** 策略版本行 ID */
    private Long id;

    /** 策略类型（PREPROCESS/CHUNK/EMBED） */
    private String type;

    /** 策略名（如 preproc-default） */
    private String name;

    /** 版本号（如 v1） */
    private String version;

    /** 配置快照（JSON 文本，完整参数：规则开关） */
    private String configSnapshot;

    /** 状态（ACTIVE/INACTIVE） */
    private String status;

    /** 创建时间 */
    private LocalDateTime createTime;
}
