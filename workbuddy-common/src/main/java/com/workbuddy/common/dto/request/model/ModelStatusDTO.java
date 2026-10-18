package com.workbuddy.common.dto.request.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 模型启停的请求体：只带目标状态。
 */
@Data
public class ModelStatusDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 目标状态 ENABLED / DISABLED（大小写不敏感，落库取大写） */
    @NotBlank(message = "状态不能为空（取值 ENABLED / DISABLED）")
    @Pattern(regexp = "(?i)ENABLED|DISABLED", message = "状态取值只能是 ENABLED 或 DISABLED")
    private String status;
}
