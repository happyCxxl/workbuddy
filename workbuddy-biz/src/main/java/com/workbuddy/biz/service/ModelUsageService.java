package com.workbuddy.biz.service;

import com.workbuddy.common.domain.model.calllog.ModelCallLogSummary;
import com.workbuddy.common.domain.model.calllog.ModelCallLogView;
import com.workbuddy.common.dto.request.model.ModelUsageQueryDto;

import java.util.List;

/**
 * 模型用量服务：调用明细查询与用量成本概览（只读）。
 *
 * <p>收参是管理接口的查询条件（时间两段为文本、维度按字典名传入），解析与非法取值拒绝在本层完成，
 * 取数交给调用明细端口。概览由明细上的聚合给出（D16：不做日聚合表），
 * 未知用量行按 null + 标记记录，占比单独给出，平均值不被未知行拉低。
 *
 * @author cxxl
 */
public interface ModelUsageService {

    /**
     * 用量与成本概览：调用总数、成功与失败数、未知用量条数与占比、已知 token 与成本合计。
     *
     * @param query 查询条件（时间范围必填）
     * @return 概览（无匹配行时计数为 0，合计与占比为 null）
     */
    ModelCallLogSummary summary(ModelUsageQueryDto query);

    /**
     * 调用明细查询：按调用时间倒序，条数由条件上的上限收口。
     *
     * @param query 查询条件（时间范围必填，其余维度可选）
     * @return 明细列表（可能为空）
     */
    List<ModelCallLogView> calls(ModelUsageQueryDto query);
}
