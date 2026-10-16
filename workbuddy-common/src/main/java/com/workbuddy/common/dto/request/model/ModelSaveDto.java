package com.workbuddy.common.dto.request.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 新增与编辑模型的请求体：厂商码、模型名、能力、长度、价格、限额。
 *
 * <p>格式与取值校验在本类注解（Controller `@Valid` 触发）；要查库查配置的三项在服务层：
 * 厂商码必须出现在 `workbuddy.model.vendors`、同厂商同名模型唯一、能力逐项按字典解析。
 *
 * @author cxxl
 */
@Data
public class ModelSaveDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 厂商码（必填，取值必须在 workbuddy.model.vendors 配置里） */
    @NotBlank(message = "厂商码不能为空")
    @Size(max = 64, message = "厂商码最长 64 字符")
    private String vendorCode;

    /** 厂商侧模型名（必填） */
    @NotBlank(message = "模型名不能为空")
    @Size(max = 128, message = "模型名最长 128 字符")
    private String modelKey;

    /** 能力清单（必填至少一项，逐项按 ModelCapability 字典解析） */
    @NotEmpty(message = "能力至少选一项")
    private List<String> capabilities;

    /** 是否支持思考模式：1 支持 / 0 不支持，留空取 0 */
    @Min(value = 0, message = "思考模式标记只接受 0（不支持）或 1（支持）")
    @Max(value = 1, message = "思考模式标记只接受 0（不支持）或 1（支持）")
    private Integer thinkingEnabled;

    /** 最大输入长度（tokens，可空） */
    @PositiveOrZero(message = "最大输入长度不能为负数")
    private Integer inputLimit;

    /** 最大输出长度（tokens，可空） */
    @PositiveOrZero(message = "最大输出长度不能为负数")
    private Integer outputLimit;

    /** 思考模式下的最大输入长度（tokens，可空） */
    @PositiveOrZero(message = "思考模式最大输入长度不能为负数")
    private Integer thinkingInputLimit;

    /** 思考模式下的最大输出长度（tokens，可空） */
    @PositiveOrZero(message = "思考模式最大输出长度不能为负数")
    private Integer thinkingOutputLimit;

    /** 上下文窗口（tokens，可空） */
    @PositiveOrZero(message = "上下文长度不能为负数")
    private Integer contextWindow;

    /** 输入每百万 token 单价（缓存未命中，可空） */
    @PositiveOrZero(message = "输入单价（缓存未命中）不能为负数")
    private BigDecimal priceInPerM;

    /** 输入每百万 token 单价（缓存命中，可空） */
    @PositiveOrZero(message = "输入单价（缓存命中）不能为负数")
    private BigDecimal priceInPerMCacheHit;

    /** 输出每百万 token 单价（可空） */
    @PositiveOrZero(message = "输出单价不能为负数")
    private BigDecimal priceOutPerM;

    /** 币种 CNY / USD（大小写不敏感，留空时新增取 CNY、编辑沿用原值） */
    @Pattern(regexp = "(?i)CNY|USD", message = "币种取值只能是 CNY 或 USD")
    private String currency;

    /** 每分钟请求数上限（可空） */
    @PositiveOrZero(message = "RPM 上限不能为负数")
    private Integer rpmLimit;

    /** 每分钟 token 上限（可空） */
    @PositiveOrZero(message = "TPM 上限不能为负数")
    private Integer tpmLimit;
}
