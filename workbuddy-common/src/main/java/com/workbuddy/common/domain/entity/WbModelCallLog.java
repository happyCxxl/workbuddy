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
 * 模型调用明细（表：wb_model_call_log）：一次逻辑调用一行，成功与失败都落行。
 *
 * <p>用量口径（D19）：厂商未回 usage 时三项 token 与 cost 都为 null，并置 {@code usageUnknown = 1}；
 * 确实为零写 0，未知与零不混用。单价随行留档，一行成本可复算。
 * 延迟是一次逻辑调用的整段耗时，重试次数由真实 HTTP 层计数（尝试次数 − 1）。
 *
 * <p>业务归属、链路标识与路由 / 降级字段由编排与路由侧补齐，本片写入时留空。
 * 写入方式是同步插入一行；若将来写入量成为瓶颈，再评估异步批量或分区。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wb_model_call_log")
public class WbModelCallLog extends BaseInfo {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 链路标识（业务链路关联用，可空） */
    private String traceId;

    /** 租户 ID（可空） */
    private Long tenantId;

    /** 业务类型（可空） */
    private String bizType;

    /** 业务 ID（可空） */
    private String bizId;

    /** 能力（取值见 ModelCapability） */
    private String capability;

    /** 厂商码 */
    private String vendor;

    /** 实际使用的模型（别名漂移以响应回传为准） */
    private String model;

    /** 响应回传的模型版本（可空） */
    private String modelVersion;

    /** 提示词 token 数（未知为 null） */
    private Long promptTokens;

    /** 生成 token 数（未知为 null） */
    private Long completionTokens;

    /** 总 token 数（未知为 null） */
    private Long totalTokens;

    /** 用量是否未知：1 未知 / 0 已知 */
    private Integer usageUnknown;

    /** 计价用的输入单价（每百万 token，可空） */
    private BigDecimal unitPriceIn;

    /** 计价用的输出单价（每百万 token，可空） */
    private BigDecimal unitPriceOut;

    /** 币种（可空） */
    private String currency;

    /** 调用费用（可空） */
    private BigDecimal cost;

    /** 延迟（毫秒，可空） */
    private Integer latencyMs;

    /** 重试次数（真实 HTTP 层计数） */
    private Integer retryCount;

    /** 由哪个模型降级而来（可空） */
    private String fallbackFrom;

    /** 路由原因（可空，取值见 RouteReason） */
    private String routeReason;

    /** 是否命中缓存：1 命中 / 0 未命中 */
    private Integer cacheHit;

    /** 调用状态（取值见 CallStatus） */
    private String status;

    /** 失败错误码（成功为 null） */
    private String errorCode;
}
