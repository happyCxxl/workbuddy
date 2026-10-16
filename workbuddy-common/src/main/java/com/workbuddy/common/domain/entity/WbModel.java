package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workbuddy.common.domain.base.BaseInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * 模型目录（表：wb_model）：一行 = 一个模型的厂商归属、能力、价格与限额。
 *
 * <p>厂商连接与密钥在配置（knowledge.model.vendors.*），本行只存厂商码；能力是多值（逗号分隔，D26）。
 * 限额只写在本行（无厂商级限额，D30）。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wb_model")
public class WbModel extends BaseInfo {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 厂商码（knowledge.model.vendors 配置里的键） */
    private String vendorCode;

    /** 厂商侧模型名 */
    private String modelKey;

    /** 能力清单（多值，逗号分隔） */
    private String capabilities;

    /** 是否支持思考模式（1 支持 / 0 不支持）：仅登记能力，不参与请求参数装配 */
    private Integer thinkingEnabled;

    /** 最大输入长度（tokens，可空） */
    private Integer inputLimit;

    /** 最大输出长度（tokens，可空） */
    private Integer outputLimit;

    /** 思考模式下的最大输入长度（tokens，可空；思考模式关闭时留空） */
    private Integer thinkingInputLimit;

    /** 思考模式下的最大输出长度（tokens，可空；思考模式关闭时留空） */
    private Integer thinkingOutputLimit;

    /** 上下文窗口（tokens，可空） */
    private Integer contextWindow;

    /** 输入每百万 token 单价（缓存未命中，可空） */
    private BigDecimal priceInPerM;

    /** 输入每百万 token 单价（缓存命中，可空） */
    private BigDecimal priceInPerMCacheHit;

    /** 输出每百万 token 单价（可空） */
    private BigDecimal priceOutPerM;

    /** 币种：CNY / USD */
    private String currency;

    /** 每分钟请求数上限（可空） */
    private Integer rpmLimit;

    /** 每分钟 token 上限（可空） */
    private Integer tpmLimit;

    /** 状态码值：ENABLED 启用 / DISABLED 停用 */
    private String status;
}
