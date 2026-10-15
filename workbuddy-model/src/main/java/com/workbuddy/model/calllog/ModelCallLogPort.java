package com.workbuddy.model.calllog;

import com.workbuddy.common.domain.model.calllog.ModelCallLogEntry;
import com.workbuddy.common.domain.model.calllog.ModelCallLogQuery;
import com.workbuddy.common.domain.model.calllog.ModelCallLogSummary;
import com.workbuddy.common.domain.model.calllog.ModelCallLogView;

import java.util.List;

/**
 * 模型调用明细 Port：明细写入、按时间范围与维度的查询、时间范围上的概览聚合。
 *
 * <p>写入以「一次逻辑调用一行」为准（成功与失败都落行）；查询必须限定时间范围，取数走明细表上的时间与维度索引。
 * 不做日聚合表（D16）：概览类统计由明细上的聚合查询给出。
 * 返回类型都是 common 里的明细类型，库表实体与 Mapper 不进入本模块。
 *
 * @author cxxl
 */
public interface ModelCallLogPort {

    /**
     * 落一条调用明细：同步插入一行，调用结束后立即写入。
     *
     * @param entry 明细入参（用量、单价快照、成本、延迟、重试与状态）
     */
    void record(ModelCallLogEntry entry);

    /**
     * 按时间范围与维度查明细：时间范围必填，按调用时间倒序，条数由条件上的上限收口。
     *
     * @param query 查询条件（时间范围必填）
     * @return 明细列表（可能为空）
     * @throws com.workbuddy.common.exception.KnowledgeException 时间范围缺失或倒置
     */
    List<ModelCallLogView> listCalls(ModelCallLogQuery query);

    /**
     * 概览聚合：同一组条件下统计调用总数、成功失败数、未知用量条数与占比、已知 token 与成本合计。
     *
     * @param query 查询条件（时间范围必填）
     * @return 概览（无匹配行时计数为 0，合计与占比为 null）
     * @throws com.workbuddy.common.exception.KnowledgeException 时间范围缺失或倒置
     */
    ModelCallLogSummary summarize(ModelCallLogQuery query);
}
