package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workbuddy.common.domain.base.BaseInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型目录（表：kb_model）：一行 = 一个模型的厂商归属、能力、价格与限额。
 *
 * <p>厂商连接与密钥在配置（knowledge.model.vendors.*），本行只存厂商码；能力是多值（逗号分隔，D26）。
 * 限额只写在本行（无厂商级限额，D30）。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("kb_model")
public class KbModel extends BaseInfo {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 厂商码（knowledge.model.vendors 配置里的键） */
    private String vendorCode;

    /** 厂商侧模型名 */
    private String modelKey;

    /** 页面展示名 */
    private String displayName;

    /** 能力清单（多值，逗号分隔） */
    private String capabilities;

    /** 上下文窗口（tokens，可空） */
    private Integer contextWindow;

    /** 输入每百万 token 单价（可空） */
    private BigDecimal priceInPerM;

    /** 输出每百万 token 单价（可空） */
    private BigDecimal priceOutPerM;

    /** 图像按页单价（可空） */
    private BigDecimal priceImagePerPage;

    /** 嵌入按千次单价（可空） */
    private BigDecimal pricePerThousandCalls;

    /** 币种：CNY / USD */
    private String currency;

    /** 每分钟请求数上限（可空） */
    private Integer rpmLimit;

    /** 每分钟 token 上限（可空） */
    private Integer tpmLimit;

    /** 状态码值：ENABLED 启用 / DISABLED 停用 */
    private String status;

    /** 生效开始时间（空 = 立即生效） */
    private LocalDateTime effectiveFrom;

    /** 生效结束时间（空 = 长期有效） */
    private LocalDateTime effectiveTo;

    /** 备注 */
    private String remark;
}
