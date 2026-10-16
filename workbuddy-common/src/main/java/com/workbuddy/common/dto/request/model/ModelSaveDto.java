package com.workbuddy.common.dto.request.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 新增与编辑模型的请求体：厂商码、模型名、能力、长度、价格、限额与状态。
 *
 * <p>厂商码取配置（`knowledge.model.vendors`）里的键，服务层在保存时校验；能力是多值，取值按字典解析；
 * 长度四列、价格三列与限额两列都可留空，给了值必须非负。
 *
 * @author cxxl
 */
@Data
public class ModelSaveDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 厂商码（必填，取值必须在配置里） */
    private String vendorCode;

    /** 厂商侧模型名（必填） */
    private String modelKey;

    /** 能力清单（多值，必填至少一项） */
    private List<String> capabilities;

    /** 是否支持思考模式（可空，留空取 0 不支持；取值 1 / 0） */
    private Integer thinkingEnabled;

    /** 最大输入长度（tokens，可空，非负） */
    private Integer inputLimit;

    /** 最大输出长度（tokens，可空，非负） */
    private Integer outputLimit;

    /** 思考模式下的最大输入长度（tokens，可空，非负） */
    private Integer thinkingInputLimit;

    /** 思考模式下的最大输出长度（tokens，可空，非负） */
    private Integer thinkingOutputLimit;

    /** 上下文窗口（tokens，可空，非负） */
    private Integer contextWindow;

    /** 输入每百万 token 单价·缓存未命中（可空，非负） */
    private BigDecimal priceInPerM;

    /** 输入每百万 token 单价·缓存命中（可空，非负） */
    private BigDecimal priceInPerMCacheHit;

    /** 输出每百万 token 单价（可空，非负） */
    private BigDecimal priceOutPerM;

    /** 币种（可空，留空取 CNY；取值 CNY / USD） */
    private String currency;

    /** 每分钟请求数上限（可空，非负） */
    private Integer rpmLimit;

    /** 每分钟 token 上限（可空，非负） */
    private Integer tpmLimit;
}
