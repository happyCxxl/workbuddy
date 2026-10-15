package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.ModelUsageService;
import com.workbuddy.common.domain.model.calllog.ModelCallLogQuery;
import com.workbuddy.common.domain.model.calllog.ModelCallLogSummary;
import com.workbuddy.common.domain.model.calllog.ModelCallLogView;
import com.workbuddy.common.domain.model.catalog.ModelCapability;
import com.workbuddy.common.dto.request.model.ModelUsageQueryDto;
import com.workbuddy.common.enums.model.CallStatus;
import com.workbuddy.common.enums.model.RouteReason;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.calllog.ModelCallLogPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.function.Function;

/**
 * 模型用量服务实现：把管理接口的收参解析成端口查询条件，取数交给调用明细端口。
 *
 * <p>解析口径：时间两段接受日期（按当日零点）与日期时间（ISO）两种写法，缺失或格式不合法按参数错误拒绝；
 * 能力、路由原因与调用状态按字典名解析，取值不在字典内也按参数错误拒绝，不做静默忽略。
 *
 * @author cxxl
 */
@Service
@RequiredArgsConstructor
public class ModelUsageServiceImpl implements ModelUsageService {

    /** 日期写法的文本长度（yyyy-MM-dd） */
    private static final int DATE_TEXT_LENGTH = 10;

    private final ModelCallLogPort modelCallLogPort;

    @Override
    public ModelCallLogSummary summary(ModelUsageQueryDto query) {
        return modelCallLogPort.summarize(queryOf(query));
    }

    @Override
    public List<ModelCallLogView> calls(ModelUsageQueryDto query) {
        return modelCallLogPort.listCalls(queryOf(query));
    }

    /** 收参 → 端口查询条件：时间两段必填，维度取值按字典解析 */
    private ModelCallLogQuery queryOf(ModelUsageQueryDto query) {
        ModelUsageQueryDto source = NullUtil.isNull(query) ? new ModelUsageQueryDto() : query;
        ModelCallLogQuery target = new ModelCallLogQuery();
        target.setFrom(timeOf(source.getFrom(), "from"));
        target.setTo(timeOf(source.getTo(), "to"));
        target.setTenantId(source.getTenantId());
        target.setCapability(dictionaryOf(source.getCapability(), ModelCapability::of, "能力"));
        target.setModel(StrUtil.trimToNull(source.getModel()));
        target.setRouteReason(dictionaryOf(source.getRouteReason(), RouteReason::of, "路由原因"));
        target.setStatus(dictionaryOf(source.getStatus(), CallStatus::of, "调用状态"));
        target.setLimit(source.getLimit());
        return target;
    }

    /** 时间解析：日期写法按当日零点，其余按 ISO 日期时间；缺失与格式不合法都按参数错误拒绝 */
    private LocalDateTime timeOf(String text, String field) {
        ThrowUtil.throwIf(StrUtil.isBlank(text), ErrorCode.PARAM_INVALID, "查询必须给出时间范围：" + field);
        String value = text.trim();
        try {
            if (value.length() == DATE_TEXT_LENGTH) {
                return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
            }
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            throw new KnowledgeException(ErrorCode.PARAM_INVALID,
                    "时间格式不合法：" + field + "=" + value + "，按 yyyy-MM-dd 或 yyyy-MM-ddTHH:mm:ss 传入");
        }
    }

    /** 字典取值解析：留空即不按该维度过滤，填了取值不在字典内按参数错误拒绝 */
    private <T> T dictionaryOf(String text, Function<String, T> parse, String field) {
        T value = parse.apply(text);
        ThrowUtil.throwIf(StrUtil.isNotBlank(text) && NullUtil.isNull(value), ErrorCode.PARAM_INVALID,
                field + "取值不在字典内：" + text);
        return value;
    }
}
