package com.workbuddy.biz.service;

import com.workbuddy.common.dto.request.retrieval.SearchDTO;
import com.workbuddy.common.dto.response.retrieval.RetrievalRunVO;
import com.workbuddy.common.dto.response.retrieval.SearchVO;

import java.util.List;

/**
 * 检索引擎编排：生产与测试台**同一执行引擎**。
 * 生产检索：不传 ruleId/versionId → 回退链（在线版本行 → kb 默认 → 引擎基线）；
 * 测试台检索：显式 (versionId, ruleId)，可检索候选冻结集，执行即落运行记录。
 */
public interface SearchService {

    /**
     * 检索（唯一执行路径）：规则解析 → 双通道召回 → RRF 融合 → 父片展开 → Top-K → 溯源返回。
     */
    SearchVO search(Long knowledgeBaseId, SearchDTO request);

    /**
     * 运行记录列表（新→旧；limit 截断，≤0 = 全部）。
     */
    List<RetrievalRunVO> listRuns(Long knowledgeBaseId, int limit);

    /**
     * 勾选对比：按运行记录回放执行时刻快照并排（**不重跑**——append-only 集合不可重放，快照即证据）。
     */
    List<SearchVO> compareRuns(Long knowledgeBaseId, List<Long> runIds);
}
