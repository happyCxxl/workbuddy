package com.workbuddy.biz.service.calllog;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.workbuddy.biz.mapper.WbModelCallLogMapper;
import com.workbuddy.common.domain.entity.WbModelCallLog;
import com.workbuddy.common.domain.model.calllog.ModelCallLogEntry;
import com.workbuddy.common.domain.model.calllog.ModelCallLogQuery;
import com.workbuddy.common.domain.model.calllog.ModelCallLogSummary;
import com.workbuddy.common.domain.model.calllog.ModelCallLogView;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.enums.model.CallStatus;
import com.workbuddy.common.enums.model.RouteReason;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import com.workbuddy.model.calllog.ModelCallLogPort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 调用明细的库表实现（表：wb_model_call_log），按端口契约写入与查询。
 *
 * <p>写入口径：一次逻辑调用一行，成功与失败都落行，同步插入；入库量级小、实现简单，
 * 若将来写入量成为瓶颈，再评估异步批量或分区。
 *
 * <p>查询口径：时间范围必填（左闭右开），维度条件按需叠加，明细按调用时间倒序，条数由条件上的上限收口；
 * 概览在同一组条件下由明细上的聚合给出（D16：不做日聚合表）。
 * 未知用量（{@code usage_unknown = 1}）的行 token 与成本为 null，聚合天然跳过，未知条数由计数列单独给出。
 *
 * @author cxxl
 */
@Service
public class DbModelCallLogService extends InfraDbServiceImpl<WbModelCallLogMapper, WbModelCallLog>
        implements ModelCallLogPort {

    /** 未知占比的小数位 */
    private static final int RATIO_SCALE = 4;

    /** 时间范围缺失时的说明 */
    private static final String TIME_RANGE_MISSING_DETAIL = "调用明细查询必须给出时间范围（from 与 to）";

    /** 时间范围倒置时的说明 */
    private static final String TIME_RANGE_ORDER_DETAIL = "调用明细查询的起始时间必须早于结束时间";

    @Override
    public void record(ModelCallLogEntry entry) {
        ThrowUtil.throwIf(NullUtil.isNull(entry), ErrorCode.PARAM_INVALID, "调用明细入参不能为空");
        save(toEntity(entry));
    }

    @Override
    public List<ModelCallLogView> listCalls(ModelCallLogQuery query) {
        requireTimeRange(query);
        LambdaQueryWrapper<WbModelCallLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(WbModelCallLog::getCreateTime, query.getFrom())
                .lt(WbModelCallLog::getCreateTime, query.getTo())
                .eq(NullUtil.isNotNull(query.getTenantId()), WbModelCallLog::getTenantId, query.getTenantId())
                .eq(NullUtil.isNotNull(query.getCapability()), WbModelCallLog::getCapability,
                        nameOf(query.getCapability()))
                .eq(StrUtil.isNotBlank(query.getModel()), WbModelCallLog::getModel, query.getModel())
                .eq(NullUtil.isNotNull(query.getRouteReason()), WbModelCallLog::getRouteReason,
                        nameOf(query.getRouteReason()))
                .eq(NullUtil.isNotNull(query.getStatus()), WbModelCallLog::getStatus, nameOf(query.getStatus()))
                .orderByDesc(WbModelCallLog::getCreateTime)
                .last("LIMIT " + query.limitOrDefault());
        List<WbModelCallLog> rows = list(wrapper);
        List<ModelCallLogView> views = new ArrayList<>(rows.size());
        for (WbModelCallLog row : rows) {
            views.add(toView(row));
        }
        return views;
    }

    @Override
    public ModelCallLogSummary summarize(ModelCallLogQuery query) {
        requireTimeRange(query);
        QueryWrapper<WbModelCallLog> wrapper = new QueryWrapper<>();
        wrapper.select(aggregateColumns())
                .ge("create_time", query.getFrom())
                .lt("create_time", query.getTo())
                .eq(NullUtil.isNotNull(query.getTenantId()), "tenant_id", query.getTenantId())
                .eq(NullUtil.isNotNull(query.getCapability()), "capability", nameOf(query.getCapability()))
                .eq(StrUtil.isNotBlank(query.getModel()), "model", query.getModel())
                .eq(NullUtil.isNotNull(query.getRouteReason()), "route_reason", nameOf(query.getRouteReason()))
                .eq(NullUtil.isNotNull(query.getStatus()), "status", nameOf(query.getStatus()));
        ModelCallLogSummary summary = new ModelCallLogSummary();
        fillCounts(summary, Map.of());
        List<Map<String, Object>> rows = baseMapper.selectMaps(wrapper);
        if (!rows.isEmpty()) {
            fillCounts(summary, rows.getFirst());
        }
        summary.setUnknownRatio(ratio(summary.getUnknownCalls(), summary.getCalls()));
        return summary;
    }

    /**
     * 聚合列：调用总数、成功与失败数、超时与限流数、未知用量条数、已知 token 与成本合计。
     * 合计列在无匹配行或全部未知时为 NULL，逐行跳过未知值；计数列在无匹配行时按 0 归位。
     */
    private String[] aggregateColumns() {
        String success = CallStatus.SUCCESS.name();
        return new String[]{
                "COUNT(*) AS calls",
                "SUM(CASE WHEN status = '" + success + "' THEN 1 ELSE 0 END) AS successCalls",
                "SUM(CASE WHEN status <> '" + success + "' THEN 1 ELSE 0 END) AS failedCalls",
                "SUM(CASE WHEN status = '" + CallStatus.TIMEOUT.name() + "' THEN 1 ELSE 0 END) AS timeoutCalls",
                "SUM(CASE WHEN status = '" + CallStatus.RATE_LIMITED.name() + "' THEN 1 ELSE 0 END) AS rateLimitedCalls",
                "SUM(CASE WHEN usage_unknown = 1 THEN 1 ELSE 0 END) AS unknownCalls",
                "SUM(total_tokens) AS totalTokens",
                "SUM(cost) AS costTotal"
        };
    }

    /** 聚合行 → 概览：计数列缺值按 0 归位（无匹配行），合计列缺值保持 null（未知不按 0 记） */
    private void fillCounts(ModelCallLogSummary summary, Map<String, Object> row) {
        summary.setCalls(countOf(row.get("calls")));
        summary.setSuccessCalls(countOf(row.get("successCalls")));
        summary.setFailedCalls(countOf(row.get("failedCalls")));
        summary.setTimeoutCalls(countOf(row.get("timeoutCalls")));
        summary.setRateLimitedCalls(countOf(row.get("rateLimitedCalls")));
        summary.setUnknownCalls(countOf(row.get("unknownCalls")));
        summary.setTotalTokens(sumOf(row.get("totalTokens")));
        summary.setCostTotal(decimalOf(row.get("costTotal")));
    }

    /** 时间范围校验：两段都必填且起始早于结束（区间左闭右开） */
    private void requireTimeRange(ModelCallLogQuery query) {
        ThrowUtil.throwIf(NullUtil.isNull(query) || NullUtil.isNull(query.getFrom()) || NullUtil.isNull(query.getTo()),
                ErrorCode.PARAM_INVALID, TIME_RANGE_MISSING_DETAIL);
        ThrowUtil.throwIf(!query.getFrom().isBefore(query.getTo()), ErrorCode.PARAM_INVALID, TIME_RANGE_ORDER_DETAIL);
    }

    /** 明细入参 → 库表行：业务归属、链路标识与路由字段按传入值落库（本片留空） */
    private WbModelCallLog toEntity(ModelCallLogEntry entry) {
        WbModelCallLog row = new WbModelCallLog();
        row.setTraceId(entry.getTraceId());
        row.setTenantId(entry.getTenantId());
        row.setBizType(entry.getBizType());
        row.setBizId(entry.getBizId());
        row.setCapability(nameOf(entry.getCapability()));
        row.setVendor(entry.getVendor());
        row.setModel(entry.getModel());
        row.setModelVersion(entry.getModelVersion());
        row.setPromptTokens(entry.getPromptTokens());
        row.setCompletionTokens(entry.getCompletionTokens());
        row.setTotalTokens(entry.getTotalTokens());
        row.setUsageUnknown(entry.getUsageUnknown());
        row.setUnitPriceIn(entry.getUnitPriceIn());
        row.setUnitPriceOut(entry.getUnitPriceOut());
        row.setCurrency(entry.getCurrency());
        row.setCost(entry.getCost());
        row.setLatencyMs(entry.getLatencyMs());
        row.setRetryCount(entry.getRetryCount());
        row.setFallbackFrom(entry.getFallbackFrom());
        row.setRouteReason(nameOf(entry.getRouteReason()));
        row.setCacheHit(entry.getCacheHit());
        row.setStatus(nameOf(entry.getStatus()));
        row.setErrorCode(entry.getErrorCode());
        return row;
    }

    /** 库表行 → 明细视图：字典列按取值解析，未知取值留空 */
    private ModelCallLogView toView(WbModelCallLog row) {
        ModelCallLogView view = new ModelCallLogView();
        view.setId(row.getId());
        view.setTraceId(row.getTraceId());
        view.setTenantId(row.getTenantId());
        view.setBizType(row.getBizType());
        view.setBizId(row.getBizId());
        view.setCapability(ModelCapability.of(row.getCapability()));
        view.setVendor(row.getVendor());
        view.setModel(row.getModel());
        view.setModelVersion(row.getModelVersion());
        view.setPromptTokens(row.getPromptTokens());
        view.setCompletionTokens(row.getCompletionTokens());
        view.setTotalTokens(row.getTotalTokens());
        view.setUsageUnknown(row.getUsageUnknown());
        view.setUnitPriceIn(row.getUnitPriceIn());
        view.setUnitPriceOut(row.getUnitPriceOut());
        view.setCurrency(row.getCurrency());
        view.setCost(row.getCost());
        view.setLatencyMs(row.getLatencyMs());
        view.setRetryCount(row.getRetryCount());
        view.setFallbackFrom(row.getFallbackFrom());
        view.setRouteReason(RouteReason.of(row.getRouteReason()));
        view.setCacheHit(row.getCacheHit());
        view.setStatus(CallStatus.of(row.getStatus()));
        view.setErrorCode(row.getErrorCode());
        view.setCreateTime(row.getCreateTime());
        return view;
    }

    /** 字典枚举的落库形态：取枚举名，空值保持空 */
    private String nameOf(Enum<?> value) {
        return NullUtil.isNull(value) ? null : value.name();
    }

    /** 计数列：缺值与非数值都按 0（无匹配行） */
    private Long countOf(Object value) {
        Long count = numberOf(value);
        if (NullUtil.isNull(count)) {
            return 0L;
        }
        return count;
    }

    /** 合计列：缺值与非数值都保持 null（未知不按 0 记） */
    private Long sumOf(Object value) {
        return numberOf(value);
    }

    /** 未知占比：未知条数 ÷ 调用总数，四位小数；无调用时为 null */
    private BigDecimal ratio(Long unknownCalls, Long calls) {
        if (NullUtil.isNull(calls) || calls < 1) {
            return null;
        }
        long unknown = NullUtil.isNull(unknownCalls) ? 0L : unknownCalls;
        return BigDecimal.valueOf(unknown).divide(BigDecimal.valueOf(calls), RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /** 数值列 → 长整数；非数值与空值返回 null */
    private Long numberOf(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /** 金额列 → 小数（驱动按 decimal 回传 BigDecimal，其余数值形态按字面量转换；非数值返回 null） */
    private BigDecimal decimalOf(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return value instanceof Number number ? new BigDecimal(number.toString()) : null;
    }
}
