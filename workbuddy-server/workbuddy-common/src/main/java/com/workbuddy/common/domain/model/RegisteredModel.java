package com.workbuddy.common.domain.model;

import com.workbuddy.common.enums.model.ModelCapability;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 内存注册表行：一条录入模型的运行时视图。
 *
 * <p>biz 装载器把 wb_model 的启用行映射成本类型（能力 CSV 解析一次）；
 * 能力用于候选筛选，长度/价格/限额供路由与容量判断使用。
 */
@Data
public class RegisteredModel {

    /** 厂商码（knowledge.model.vendors 配置里的键） */
    private String vendorCode;

    /** 厂商侧模型名（目录内以 厂商码 + 模型名 定位一行，同名跨厂商是合法录入） */
    private String modelKey;

    /** 能力清单（录入行的逗号分隔已解析成集合） */
    private Set<ModelCapability> capabilities;

    /** 上下文窗口（tokens，可空） */
    private Integer contextWindow;

    /** 最大输入长度（tokens，可空） */
    private Integer inputLimit;

    /** 最大输出长度（tokens，可空） */
    private Integer outputLimit;

    /** 输入每百万 token 单价（缓存未命中，可空） */
    private BigDecimal priceInPerM;

    /** 输入每百万 token 单价（缓存命中，可空） */
    private BigDecimal priceInPerMCacheHit;

    /** 输出每百万 token 单价（可空） */
    private BigDecimal priceOutPerM;

    /** 币种：CNY / USD */
    private String currency;

    /** 每分钟请求数上限（必填） */
    private Integer rpmLimit;

    /** 每分钟 token 上限（必填） */
    private Integer tpmLimit;

    /** 状态：ENABLED / DISABLED */
    private String status;
}
