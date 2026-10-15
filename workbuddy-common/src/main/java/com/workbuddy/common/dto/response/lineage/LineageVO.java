package com.workbuddy.common.dto.response.lineage;

import lombok.Data;

import java.util.List;

/**
 * 执行树聚合响应：一个文件的全部运行节点 + 血缘边。
 * 节点 = 一次运行（taskId）；边 = upstreamProductId 血缘（反查产物产出任务）。
 *
 * @author cxxl
 */
@Data
public class LineageVO {

    /** 文件结果 ID */
    private Long fileResultId;

    /** 运行节点（环节顺序 PARSE→STRUCTURE→PREPROCESS→CHUNK→EMBED，同环节按任务升序） */
    private List<LineageNodeVO> nodes;

    /** 血缘边（上游任务 → 下游任务；解析无入边，孤立节点允许存在） */
    private List<LineageEdgeVO> edges;
}
