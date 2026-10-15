package com.workbuddy.common.domain.model.calllog;

import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.enums.model.CallStatus;
import com.workbuddy.common.enums.model.RouteReason;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 模型调用明细查询条件：时间范围必填（闭开区间），其余维度可选叠加。
 *
 * <p>时间范围是必填项：明细表按 {@code (tenant_id, create_time)} 与 {@code (capability, model, create_time)}
 * 两组索引取数，全表扫描与不做时间限定的取数都不允许（D16：不做日聚合表，看板与告警直接查本表）。
 * 明细按调用时间倒序返回，取数条数由 {@code limit} 收口。
 *
 * @author cxxl
 */
@Data
public class ModelCallLogQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 明细查询默认返回条数 */
    public static final int DEFAULT_LIMIT = 500;

    /** 明细查询返回条数上限 */
    public static final int MAX_LIMIT = 2000;

    /** 起始时间（含，必填） */
    private LocalDateTime from;

    /** 结束时间（不含，必填） */
    private LocalDateTime to;

    /** 租户 ID（可选） */
    private Long tenantId;

    /** 能力（可选） */
    private ModelCapability capability;

    /** 模型名（可选） */
    private String model;

    /** 路由原因（可选） */
    private RouteReason routeReason;

    /** 调用状态（可选） */
    private CallStatus status;

    /** 返回条数上限（可空，按 {@link #DEFAULT_LIMIT} 兜底，超过 {@link #MAX_LIMIT} 按上限截断） */
    private Integer limit;

    /** 取数条数：未传或小于 1 按默认值，超过上限按上限截断 */
    public int limitOrDefault() {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
