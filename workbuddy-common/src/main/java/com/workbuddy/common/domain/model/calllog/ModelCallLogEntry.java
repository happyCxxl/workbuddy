package com.workbuddy.common.domain.model.calllog;

import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.enums.model.CallStatus;
import com.workbuddy.common.enums.model.RouteReason;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 模型调用明细入参：一次逻辑调用一行的全部可写字段，由 knowledge-model 组装、knowledge-biz 落库。
 *
 * <p>用量口径（D19）：厂商未回 usage 时三项 token 与 cost 都为 null，且 {@code usageUnknown = 1}；
 * 确实为零与未知是两件事，前者写 0、后者写 null 并用标记位区分。
 * 单价随行留档（{@code unitPriceIn} / {@code unitPriceOut} / {@code currency}），一行成本可复算。
 *
 * <p>路由与降级链字段（{@code routeReason} / {@code fallbackFrom}）在本片留空，取值域按字典先建好；
 * 业务归属字段（{@code tenantId} / {@code bizType} / {@code bizId}）与链路标识 {@code traceId} 同样留空，
 * 由编排侧在同一份上下文里补齐。库表实体不进入 knowledge-model。
 *
 * @author cxxl
 */
@Data
public class ModelCallLogEntry implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 链路标识（业务链路关联用，可空） */
    private String traceId;

    /** 租户 ID（可空） */
    private Long tenantId;

    /** 业务类型（如解析任务 / 问答会话，可空） */
    private String bizType;

    /** 业务 ID（可空） */
    private String bizId;

    /** 能力 */
    private ModelCapability capability;

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

    /** 用量是否未知：1 未知（tokens 与 cost 记 null）/ 0 已知 */
    private Integer usageUnknown;

    /** 计价用的输入单价（每百万 token，可空） */
    private BigDecimal unitPriceIn;

    /** 计价用的输出单价（每百万 token，可空） */
    private BigDecimal unitPriceOut;

    /** 币种（可空） */
    private String currency;

    /** 调用费用（用量未知或单价缺失时为 null） */
    private BigDecimal cost;

    /** 延迟（毫秒，一次逻辑调用的整段耗时，含内部重试与退避等待；可空） */
    private Integer latencyMs;

    /** 重试次数（真实 HTTP 层计数：尝试次数 − 1） */
    private Integer retryCount;

    /** 由哪个模型降级而来（可空） */
    private String fallbackFrom;

    /** 路由原因（可空） */
    private RouteReason routeReason;

    /** 是否命中缓存：1 命中 / 0 未命中 */
    private Integer cacheHit;

    /** 调用状态 */
    private CallStatus status;

    /** 失败错误码（成功为 null） */
    private String errorCode;
}
