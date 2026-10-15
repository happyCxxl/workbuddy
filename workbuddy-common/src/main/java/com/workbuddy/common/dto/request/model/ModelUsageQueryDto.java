package com.workbuddy.common.dto.request.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 调用明细与概览的查询条件（管理接口收参）：时间范围必填，其余维度可选。
 *
 * <p>时间两段接受两种写法：日期（`yyyy-MM-dd`，按当日零点）与日期时间（`yyyy-MM-ddTHH:mm:ss`）；
 * 区间为左闭右开。维度与字典取值按名称传入，解析与非法取值拒绝落在服务层。
 *
 * @author cxxl
 */
@Data
public class ModelUsageQueryDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 起始时间（含，必填） */
    private String from;

    /** 结束时间（不含，必填） */
    private String to;

    /** 租户 ID（可选） */
    private Long tenantId;

    /** 能力（可选，字典名：CHAT / EMBEDDING / OCR / LAYOUT / TABLE_STRUCTURE） */
    private String capability;

    /** 模型名（可选） */
    private String model;

    /** 路由原因（可选，字典名：OK / CAPACITY_HIGH / LATENCY_HIGH / UNHEALTHY / SLOW_START / FALLBACK_FROM） */
    private String routeReason;

    /** 调用状态（可选，字典名：SUCCESS / FAILED / TIMEOUT / RATE_LIMITED / CIRCUIT_OPEN） */
    private String status;

    /** 返回条数上限（可选；只对明细查询生效，未传按默认值，超过上限按上限截断） */
    private Integer limit;
}
