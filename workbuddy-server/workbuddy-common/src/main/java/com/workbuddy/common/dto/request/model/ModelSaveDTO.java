package com.workbuddy.common.dto.request.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
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
 * <p>校验全部在本类注解（Controller `@Valid` 触发）；服务层只做能力逐项按字典解析，
 * 唯一键冲突由 `uk_vendor_model` 索引拦下并转业务异常。
 */
@Data
public class ModelSaveDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 厂商码（必填，服务层按 workbuddy.model.vendors 的键校验）
     */
    @NotBlank(message = "厂商码不能为空")
    @Size(max = 64, message = "厂商码最长 64 字符")
    private String vendorCode;

    /**
     * 厂商侧模型名（必填）
     */
    @NotBlank(message = "模型名不能为空")
    @Size(max = 128, message = "模型名最长 128 字符")
    private String modelKey;

    /**
     * 能力清单（必填至少一项，逐项按 ModelCapability 字典解析）
     */
    @NotEmpty(message = "能力至少选一项")
    private List<String> capabilities;

    /**
     * 是否支持思考模式：1 支持 / 0 不支持，留空取 0
     */
    @Min(value = 0, message = "思考模式标记只接受 0（不支持）或 1（支持）")
    @Max(value = 1, message = "思考模式标记只接受 0（不支持）或 1（支持）")
    private Integer thinkingEnabled;

    /**
     * 最大输入长度（tokens，可空）
     */
    @PositiveOrZero(message = "最大输入长度不能为负数")
    private Integer inputLimit;

    /**
     * 最大输出长度（tokens，可空）
     */
    @PositiveOrZero(message = "最大输出长度不能为负数")
    private Integer outputLimit;

    /**
     * 思考模式下的最大输入长度（tokens，可空）
     */
    @PositiveOrZero(message = "思考模式最大输入长度不能为负数")
    private Integer thinkingInputLimit;

    /**
     * 思考模式下的最大输出长度（tokens，可空）
     */
    @PositiveOrZero(message = "思考模式最大输出长度不能为负数")
    private Integer thinkingOutputLimit;

    /**
     * 上下文窗口（tokens，可空）
     */
    @PositiveOrZero(message = "上下文长度不能为负数")
    private Integer contextWindow;

    /**
     * 输入每百万 token 单价（缓存未命中，可空）
     */
    @PositiveOrZero(message = "输入单价（缓存未命中）不能为负数")
    private BigDecimal priceInPerM;

    /**
     * 输入每百万 token 单价（缓存命中，可空）
     */
    @PositiveOrZero(message = "输入单价（缓存命中）不能为负数")
    private BigDecimal priceInPerMCacheHit;

    /**
     * 输出每百万 token 单价（可空）
     */
    @PositiveOrZero(message = "输出单价不能为负数")
    private BigDecimal priceOutPerM;

    /**
     * 币种 CNY / USD（必填，原样落库）
     */
    @NotBlank(message = "币种不能为空")
    @Pattern(regexp = "CNY|USD", message = "币种取值只能是 CNY 或 USD")
    private String currency;

    /**
     * 每分钟请求数上限（必填）
     */
    @NotNull(message = "RPM 上限不能为空")
    @Positive(message = "RPM 上限必须为正数")
    private Integer rpmLimit;

    /**
     * 每分钟 token 上限（必填）
     */
    @NotNull(message = "TPM 上限不能为空")
    @Positive(message = "TPM 上限必须为正数")
    private Integer tpmLimit;
}
