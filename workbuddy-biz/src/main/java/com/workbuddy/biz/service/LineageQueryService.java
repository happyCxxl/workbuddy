package com.workbuddy.biz.service;

import com.workbuddy.common.dto.response.lineage.LineageVO;

/**
 * 执行树聚合查询：一个文件的全部运行节点 + 血缘边，纯读聚合。
 *
 * @author cxxl
 */
public interface LineageQueryService {

    /**
     * 执行树聚合：任务节点（环节顺序）+ 血缘边（upstreamProductId 反查）+ 统计摘要。
     *
     * @param fileResultId 文件结果 ID（不存在 40432）
     * @return 执行树
     */
    LineageVO lineage(Long fileResultId);
}
