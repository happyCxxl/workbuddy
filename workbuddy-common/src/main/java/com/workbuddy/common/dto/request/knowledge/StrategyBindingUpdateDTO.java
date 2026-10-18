package com.workbuddy.common.dto.request.knowledge;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库-策略绑定请求。
 *
 * @author cxxl
 */
@Data
public class StrategyBindingUpdateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 策略类型：PREPROCESS / CHUNK */
    @NotBlank(message = "策略类型不能为空")
    private String strategyType;

    /** 策略版本行 ID；null = 解绑 */
    private Long strategyVersionId;
}
