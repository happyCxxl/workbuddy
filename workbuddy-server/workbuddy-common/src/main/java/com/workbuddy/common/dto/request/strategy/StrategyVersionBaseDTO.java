package com.workbuddy.common.dto.request.strategy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 策略版本创建/编辑共用字段：name / version / configSnapshot。
 */
@Data
public class StrategyVersionBaseDTO {

    /** 策略名 */
    @NotBlank(message = "name 不能为空")
    @Size(max = 128, message = "name 最长 128 字符")
    private String name;

    /** 版本号 */
    @NotBlank(message = "version 不能为空")
    @Size(max = 32, message = "version 最长 32 字符")
    private String version;

    /** 配置快照（JSON 文本） */
    @NotBlank(message = "configSnapshot 不能为空")
    private String configSnapshot;
}
