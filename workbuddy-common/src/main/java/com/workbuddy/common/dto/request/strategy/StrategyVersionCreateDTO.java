package com.workbuddy.common.dto.request.strategy;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 策略版本创建请求：type/name/version/configSnapshot 必填；configSnapshot 为 JSON 文本（规则开关）。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StrategyVersionCreateDTO extends StrategyVersionBaseDTO {

    /** 策略类型（PREPROCESS/CHUNK/EMBED/RETRIEVAL，白名单校验） */
    @NotBlank(message = "type 不能为空")
    private String type;
}
