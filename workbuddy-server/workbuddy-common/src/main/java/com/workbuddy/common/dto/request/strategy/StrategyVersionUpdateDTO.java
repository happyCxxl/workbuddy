package com.workbuddy.common.dto.request.strategy;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 策略编辑请求（策略版本行不可变：编辑 = 复制新版本——旧行原样保留，本 DTO 承载新行的 name/version/configSnapshot）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StrategyVersionUpdateDTO extends StrategyVersionBaseDTO {
}
