package com.workbuddy.common.dto.response.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型清单条目 VO：管理页面表格里的一行，字段按页面的列序排列。
 *
 * <p>能力按字典名逐个下发（页面渲染成一枚枚标签）；`vendorName` 是厂商码的展示名，配置里没有这一项；
 * 价格四列与限额两列可为空，留空表示该口径未登记。
 */
@Data
public class ModelItemVO {

    /** 主键 */
    private Long id;

    /** 厂商码 */
    private String vendorCode;

    /** 厂商展示名（内置映射命中取展示名，未命中回落厂商码） */
    private String vendorName;

    /** 厂商侧模型名（唯一键的另一半） */
    private String modelKey;

    /** 能力字典名清单，至少一项 */
    private List<String> capabilities = new ArrayList<>();

    /** 状态码值 ENABLED / DISABLED */
    private String status;

    /** 是否支持思考模式（1 支持 / 0 不支持） */
    private Integer thinkingEnabled;

    /** 最大输入长度 tokens，可空 */
    private Integer inputLimit;

    /** 最大输出长度 tokens，可空 */
    private Integer outputLimit;

    /** 思考模式下的最大输入长度 tokens，可空 */
    private Integer thinkingInputLimit;

    /** 思考模式下的最大输出长度 tokens，可空 */
    private Integer thinkingOutputLimit;

    /** 上下文窗口 tokens，可空 */
    private Integer contextWindow;

    /** 每百万 token 的输入单价（缓存未命中），可空 */
    private BigDecimal priceInPerM;

    /** 每百万 token 的输入单价（缓存命中），可空 */
    private BigDecimal priceInPerMCacheHit;

    /** 每百万 token 的输出单价，可空 */
    private BigDecimal priceOutPerM;

    /** 币种 CNY / USD */
    private String currency;

    /** 每分钟请求数上限，可空 */
    private Integer rpmLimit;

    /** 每分钟 token 上限，可空 */
    private Integer tpmLimit;
}
